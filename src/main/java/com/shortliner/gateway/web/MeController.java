package com.shortliner.gateway.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the frontend render "logged in as …" / a login button without ever seeing a token.
 * Always 200 — anonymous is a normal state here, not an error.
 */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal OidcUser user) {
        if (user == null) {
            return MeResponse.anonymous();
        }
        return new MeResponse(true, user.getSubject(), user.getPreferredUsername(), user.getEmail(), user.getFullName());
    }

    public record MeResponse(boolean authenticated, String id, String username, String email, String name) {

        static MeResponse anonymous() {
            return new MeResponse(false, null, null, null, null);
        }
    }
}
