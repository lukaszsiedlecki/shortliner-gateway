# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build                     # compile + tests
./gradlew test                      # tests only (no external services needed)
./gradlew test --tests "com.shortliner.gateway.GatewaySecurityTest"
./gradlew bootRun                   # needs KEYCLOAK_CLIENT_SECRET in the environment
```

The service starts on port `8084`.

## Architecture

Spring Cloud Gateway (**WebFlux**, not servlet — don't add `spring-boot-starter-web`) acting as the
Backend-for-Frontend for Shortliner. README.md has the route table and env vars.

- **Routes** live in `application.properties` under `spring.cloud.gateway.server.webflux.*` (the
  Spring Cloud 2025.0 prefix — older tutorials use `spring.cloud.gateway.routes`). The catch-all
  frontend route must stay last.
- **`OAuth2ClientConfig`** builds the Keycloak `ClientRegistration` in code, not via `issuer-uri`:
  the browser-facing issuer (`keycloak.local`) doesn't resolve in the cluster, so back-channel
  endpoints use `KEYCLOAK_INTERNAL_URI`, and properties can't express `end_session_endpoint`
  (needed for RP-initiated logout).
- **`SecurityConfig`**: `oauth2Login` with PKCE, OIDC logout, 401 (not redirect) for `/api/**`,
  cookie-based CSRF readable by the SPA (plain, non-XOR handler) plus a `WebFilter` that forces the
  `XSRF-TOKEN` cookie onto every response. Authorization is mostly left to the backends
  (resource servers); only `/api/payment/**` is gated here.
- **`MeController`**: `/api/me` — controllers take precedence over gateway routes.

## Tests

`GatewaySecurityTest` boots the whole app with one OkHttp `MockWebServer` standing in for every
backend and for the frontend, and uses `mockOidcLogin()` (its default access token value is
`access-token`). `MockWebServer.getRequestCount()` is cumulative across tests — assert "nothing was
forwarded" with `takeRequest(timeout) == null` instead.

## Platform conventions (homelab)

Same as the other shortliner services: the homelab Helm chart sets `MANAGEMENT_SERVER_PORT=9090`
and `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`; actuator health/prometheus must stay open; never use
unbounded values (paths, user IDs) as metric tags; never log tokens or the session cookie.
