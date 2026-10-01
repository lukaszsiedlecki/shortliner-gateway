package com.shortliner.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Backend-for-Frontend security: the browser only ever holds a session cookie; access/refresh
 * tokens stay in this service and are attached to backend calls by the {@code TokenRelay} filter.
 *
 * <p>The gateway authenticates, the backends authorize: most routes are {@code permitAll} here and
 * each backend (an OAuth2 resource server) decides what an anonymous vs. logged-in caller may do.
 * Only routes that are useless without a user are additionally gated here, so the browser gets a
 * clean 401 without a backend round trip.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ReactiveClientRegistrationRepository clientRegistrations) {
        // Keycloak's client is confidential, but PKCE still protects the code exchange
        // against authorization-code injection; Spring only enables it by default for public clients.
        var authorizationRequestResolver = new DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrations);
        authorizationRequestResolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        var logoutSuccessHandler = new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrations);
        logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/");

        // XHR calls get a 401 the frontend can react to; anything else is sent to the Keycloak login page.
        var entryPoint = new DelegatingServerAuthenticationEntryPoint(
                new DelegatingServerAuthenticationEntryPoint.DelegateEntry(
                        new PathPatternParserServerWebExchangeMatcher("/api/**"),
                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));
        entryPoint.setDefaultEntryPoint(new RedirectServerAuthenticationEntryPoint(
                "/oauth2/authorization/" + OAuth2ClientConfig.REGISTRATION_ID));

        http
                .authorizeExchange(exchanges -> exchanges
                        // K8s probes and Prometheus — must never require auth (they run on the
                        // management port in the cluster, but keep them open either way).
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .pathMatchers("/api/payment/**").authenticated()
                        .anyExchange().permitAll())
                .oauth2Login(login -> login.authorizationRequestResolver(authorizationRequestResolver))
                .logout(logout -> logout.logoutSuccessHandler(logoutSuccessHandler))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                // Session cookies are sent automatically by the browser, so state-changing requests
                // need CSRF protection. The SPA reads the XSRF-TOKEN cookie and echoes it in the
                // X-XSRF-TOKEN header (or a _csrf form field for the logout form). The plain — not
                // XOR-masked — handler is used because the SPA sends the raw cookie value back.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()));

        return http.build();
    }

    /**
     * The CSRF token is generated lazily, so without this the XSRF-TOKEN cookie would only appear
     * after the first request that happens to read it — i.e. never, for a SPA. Subscribing on every
     * request makes the cookie available from the first page load.
     */
    @Bean
    public WebFilter csrfCookieWebFilter() {
        return (exchange, chain) -> {
            Mono<CsrfToken> csrfToken = exchange.getAttributeOrDefault(CsrfToken.class.getName(), Mono.empty());
            return csrfToken.then(chain.filter(exchange));
        };
    }
}
