package com.shortliner.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.Map;

/**
 * Builds the Keycloak client registration in code rather than through
 * {@code spring.security.oauth2.client.provider.*.issuer-uri}.
 *
 * <p>With {@code issuer-uri} Spring would fetch the discovery document from that URL at startup,
 * which doesn't resolve inside the cluster (see {@link KeycloakProperties}). Listing the endpoints
 * one by one would work for login, but properties can't carry {@code end_session_endpoint}, which
 * {@code OidcClientInitiatedServerLogoutSuccessHandler} needs to log the user out of Keycloak too.
 */
@Configuration
public class OAuth2ClientConfig {

    public static final String REGISTRATION_ID = "keycloak";

    @Bean
    public ReactiveClientRegistrationRepository clientRegistrationRepository(KeycloakProperties keycloak) {
        String browser = keycloak.issuerUri() + "/protocol/openid-connect";
        String backchannel = keycloak.backchannelUri() + "/protocol/openid-connect";

        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("Keycloak")
                .clientId(keycloak.clientId())
                .clientSecret(keycloak.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                // ID tokens are validated against this, so it must be the browser-facing URL.
                .issuerUri(keycloak.issuerUri())
                .authorizationUri(browser + "/auth")
                .tokenUri(backchannel + "/token")
                .jwkSetUri(backchannel + "/certs")
                .userInfoUri(backchannel + "/userinfo")
                .userNameAttributeName("preferred_username")
                .providerConfigurationMetadata(Map.of("end_session_endpoint", browser + "/logout"))
                .build();

        return new InMemoryReactiveClientRegistrationRepository(registration);
    }
}
