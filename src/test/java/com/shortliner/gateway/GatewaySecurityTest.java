package com.shortliner.gateway;

import com.shortliner.gateway.config.OAuth2ClientConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OidcLoginMutator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;

@SpringBootTest(properties = {
        "keycloak.issuer-uri=http://keycloak.test/realms/shortliner",
        "keycloak.internal-uri=http://keycloak.internal/realms/shortliner",
        "keycloak.client-secret=test-secret"
})
@AutoConfigureWebTestClient
// Tests disable metrics exporters by default, which would leave /actuator/prometheus unmapped.
@AutoConfigureMetrics
class GatewaySecurityTest {

    // One fake upstream for every route; the recorded path tells which route forwarded it.
    private static final MockWebServer upstream = new MockWebServer();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveClientRegistrationRepository clientRegistrations;

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() throws IOException {
        upstream.shutdown();
    }

    @DynamicPropertySource
    static void routeToUpstream(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + upstream.getPort();
        registry.add("SHORTLINER_BACKEND_URL", () -> url);
        registry.add("ANALYTICS_BACKEND_URL", () -> url);
        registry.add("PAYMENT_BACKEND_URL", () -> url);
        registry.add("FRONTEND_URL", () -> url);
    }

    @BeforeEach
    void sendHostHeaderLikeABrowser() {
        // The mock exchange has no Host header, which PreserveHostHeader would forward as-is
        // (and fail on); real browsers always send one.
        webTestClient = webTestClient.mutate().defaultHeader("Host", "shortliner.local").build();
    }

    @BeforeEach
    void drainUpstream() throws InterruptedException {
        while (upstream.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // discard requests left over from a previous test
        }
    }

    @Test
    void meIsAnonymousWithoutLogin() {
        webTestClient.get().uri("/api/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authenticated").isEqualTo(false);
    }

    @Test
    void meReturnsLoggedInUser() {
        webTestClient.mutateWith(login())
                .get().uri("/api/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authenticated").isEqualTo(true)
                .jsonPath("$.id").isEqualTo("user-123")
                .jsonPath("$.username").isEqualTo("jan");
    }

    @Test
    void paymentRequiresLoginAndAnswers401InsteadOfRedirecting() throws InterruptedException {
        webTestClient.get().uri("/api/payment/api/payments/42")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(upstream.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void paymentRelaysAccessTokenAndStripsPrefixAndCookie() throws InterruptedException {
        upstream.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        webTestClient.mutateWith(login())
                .get().uri("/api/payment/api/payments/42")
                .cookie("SESSION", "browser-session")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest forwarded = upstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/api/payments/42");
        assertThat(forwarded.getHeader("Authorization")).isEqualTo("Bearer access-token");
        assertThat(forwarded.getHeader("Cookie")).isNull();
    }

    @Test
    void anonymousCallsArePassedThroughWithoutToken() throws InterruptedException {
        upstream.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "https://example.com"));

        webTestClient.get().uri("/api/shortliner/shorten/abc123")
                .exchange()
                .expectStatus().isFound();

        RecordedRequest forwarded = upstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/shorten/abc123");
        assertThat(forwarded.getHeader("Authorization")).isNull();
    }

    @Test
    void backendsSeeTheBrowserFacingHost() throws InterruptedException {
        upstream.enqueue(new MockResponse().setResponseCode(404));

        webTestClient.get().uri("/api/shortliner/shorten/abc123")
                .exchange()
                .expectStatus().isNotFound();

        RecordedRequest forwarded = upstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getHeader("Host")).isEqualTo("shortliner.local");
    }

    @Test
    void stateChangingRequestsWithoutCsrfTokenAreRejected() throws InterruptedException {
        webTestClient.post().uri("/api/shortliner/shorten")
                .bodyValue("{\"url\":\"https://example.com\"}")
                .exchange()
                .expectStatus().isForbidden();

        assertThat(upstream.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void stateChangingRequestsWithCsrfTokenAreForwarded() throws InterruptedException {
        upstream.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        webTestClient.mutateWith(csrf())
                .post().uri("/api/shortliner/shorten")
                .bodyValue("{\"url\":\"https://example.com\"}")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest forwarded = upstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/shorten");
    }

    @Test
    void pageLoadHandsOutXsrfCookie() {
        upstream.enqueue(new MockResponse().setBody("<html></html>").addHeader("Content-Type", "text/html"));

        webTestClient.get().uri("/")
                .exchange()
                .expectStatus().isOk()
                .expectCookie().exists("XSRF-TOKEN");
    }

    @Test
    void unknownPathsGoToFrontendUnchanged() throws InterruptedException {
        upstream.enqueue(new MockResponse().setBody("<html></html>").addHeader("Content-Type", "text/html"));

        webTestClient.get().uri("/some/page?x=1")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest forwarded = upstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/some/page?x=1");
    }

    @Test
    void loginRedirectsBrowserToKeycloakWithPkce() {
        webTestClient.get().uri("/oauth2/authorization/keycloak")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().value("Location", location -> assertThat(location)
                        .startsWith("http://keycloak.test/realms/shortliner/protocol/openid-connect/auth?")
                        .contains("client_id=shortliner-gateway")
                        .contains("code_challenge_method=S256"));
    }

    @Test
    void logoutEndsKeycloakSessionToo() {
        webTestClient.mutateWith(login()).mutateWith(csrf())
                .post().uri("/logout")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().value("Location", location -> assertThat(location)
                        .startsWith("http://keycloak.test/realms/shortliner/protocol/openid-connect/logout?")
                        .contains("id_token_hint=")
                        .contains("post_logout_redirect_uri="));
    }

    @Test
    void probesAreOpen() {
        webTestClient.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void prometheusIsOpen() {
        webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("application=\"shortliner-gateway\""));
    }

    private OidcLoginMutator login() {
        ClientRegistration keycloak = clientRegistrations
                .findByRegistrationId(OAuth2ClientConfig.REGISTRATION_ID)
                .block();
        return mockOidcLogin()
                .clientRegistration(keycloak)
                .idToken(token -> token
                        .subject("user-123")
                        .claim("preferred_username", "jan"));
    }
}
