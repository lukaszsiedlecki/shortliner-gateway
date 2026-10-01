package com.shortliner.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keycloak realm coordinates, split by who resolves the URL.
 *
 * <p>Keycloak always issues tokens with {@code iss = issuerUri} (the cluster pins it via
 * {@code KC_HOSTNAME=http://keycloak.local}), and the browser is redirected there for login and
 * logout. That hostname only resolves on the LAN, not in cluster DNS, so the server-to-server
 * calls (token exchange, JWKS, userinfo) go to {@code internalUri} — the in-cluster Service —
 * instead.
 *
 * @param issuerUri    realm URL as the browser sees it, e.g. {@code http://keycloak.local/realms/shortliner}
 * @param internalUri  realm URL as this service reaches it; blank means "same as issuerUri"
 * @param clientId     confidential client registered in the realm
 * @param clientSecret that client's secret
 */
@ConfigurationProperties("keycloak")
public record KeycloakProperties(
        String issuerUri,
        String internalUri,
        String clientId,
        String clientSecret
) {

    public String backchannelUri() {
        return internalUri == null || internalUri.isBlank() ? issuerUri : internalUri;
    }
}
