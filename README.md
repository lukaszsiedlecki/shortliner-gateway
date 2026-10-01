# Shortliner Gateway

Backend-for-Frontend (BFF) and single entry point for the Shortliner system, built on
**Spring Cloud Gateway (WebFlux)**. It logs users in against **Keycloak** (OIDC authorization code
flow + PKCE, confidential client), keeps their tokens server-side, and gives the browser only a
session cookie. Calls to backends get the user's access token attached (`TokenRelay`).

```
browser ──SESSION cookie──> shortliner-gateway ──Bearer JWT──> shortliner / analytics / payment
                               │  login/logout redirects
                               └──────────────> Keycloak (realm "shortliner")
                               └── /** ───────> shortliner-frontend (Next.js)
```

## Tech Stack

- Java 25, Spring Boot 3.5, Spring Cloud 2025.0 (Gateway Server WebFlux 4.3)
- Spring Security OAuth2 Client
- Micrometer (Prometheus, OpenTelemetry tracing), ECS JSON logs

## Endpoints

| Path | Handled by | Notes |
|---|---|---|
| `/oauth2/authorization/keycloak` | Spring Security | Starts login (full-page navigation, not `fetch`) |
| `/login/oauth2/code/keycloak` | Spring Security | Redirect URI registered in Keycloak |
| `POST /logout` | Spring Security | Needs CSRF token; ends the Keycloak session too, then back to `/` |
| `GET /api/me` | `MeController` | `{authenticated, id, username, email, name}` — always 200 |
| `/api/shortliner/**` | route → `SHORTLINER_BACKEND_URL` | prefix `/api/shortliner` stripped |
| `/api/analytics/**` | route → `ANALYTICS_BACKEND_URL` | prefix `/api/analytics` stripped |
| `/api/payment/**` | route → `PAYMENT_BACKEND_URL` | prefix stripped; **login required** (401 otherwise) |
| `/**` | route → `FRONTEND_URL` | everything else |

The `/api/<service>/**` paths are the same contract the frontend's own Next.js proxy used, so the
frontend's `fetch` calls don't change.

**Authentication vs. authorization:** the gateway authenticates (who is it?); backends are OAuth2
resource servers and authorize (may they?). Only `/api/payment/**` is gated here as well.

**CSRF:** because the session is a cookie, every non-GET request needs the `XSRF-TOKEN` cookie value
echoed back in the `X-XSRF-TOKEN` header (or a `_csrf` form field). The cookie is set on every
response.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8084` | |
| `KEYCLOAK_ISSUER_URI` | `http://keycloak.local/realms/shortliner` | Realm URL as the **browser** sees it (`iss`, login/logout redirects) |
| `KEYCLOAK_INTERNAL_URI` | *(empty = issuer)* | Realm URL as **this service** reaches it (token, JWKS, userinfo). In the cluster: `http://keycloak.keycloak.svc.cluster.local/realms/shortliner` |
| `KEYCLOAK_CLIENT_ID` | `shortliner-gateway` | |
| `KEYCLOAK_CLIENT_SECRET` | *(required)* | |
| `SHORTLINER_BACKEND_URL` | `http://localhost:8080` | |
| `ANALYTICS_BACKEND_URL` | `http://localhost:8082` | |
| `PAYMENT_BACKEND_URL` | `http://localhost:8083` | |
| `FRONTEND_URL` | `http://localhost:3000` | |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | *(empty)* | `ecs` in the cluster |
| `MANAGEMENT_SERVER_PORT` | *(same port)* | `9090` in the cluster — probes + `/actuator/prometheus` |

### Keycloak client

Realm `shortliner`, client `shortliner-gateway`:
- Client authentication **on** (confidential), Standard flow only, PKCE method `S256`
- Valid redirect URIs: `http://shortliner.local/login/oauth2/code/keycloak`,
  `http://localhost:8084/login/oauth2/code/keycloak`
- Valid post logout redirect URIs: `http://shortliner.local/`, `http://localhost:8084/`

## Local Development

```bash
cp .env.example .env.local   # set KEYCLOAK_CLIENT_SECRET
set -a; source .env.local; set +a
./gradlew bootRun
```

Run the frontend (`npm run dev`, port 3000) and the backends you need, then browse
**http://localhost:8084** (not :3000) so that requests go through the gateway.

```bash
./gradlew test   # no Keycloak or backends needed — MockWebServer + mocked OIDC login
```

## Limitations

- Sessions and the tokens behind them are **in memory**: a restart logs everyone out, and running
  more than one replica needs Spring Session (e.g. Redis) first.
