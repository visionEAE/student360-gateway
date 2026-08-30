# student360-gateway

Single entry point of Student 360° (port **8080**, Spring Cloud Gateway on WebFlux). It answers
three questions for every request and forwards nothing until all three are settled:

1. **Who is calling?** The access token is validated against the SSO's JWKS —
   `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` is the single line that binds the
   platform to its identity provider; point it at the institutional IdP and the custom SSO is gone.
2. **May this role reach this route?** Coarse authorization (`SecurityConfiguration`):

   | Route | Roles |
   |---|---|
   | `/api/auth/**` | public (forwarded untouched, SSO does its own checks) |
   | `/api/core/**`, `/api/lms/**` | `STUDENT`, `ADVISOR`, `ADMIN` |
   | `/api/support/students/**` | `STUDENT`, `ADVISOR` |
   | `/api/support/advisors/**` | `ADVISOR`, `ADMIN` |

   The fine-grained question — may *this* student or advisor see *that* student — is answered
   downstream by the service that owns the data.
3. **What does the downstream service receive?** (`IdentityRewriteGlobalFilter`) The user's
   token is stripped; the validated identity travels as `X-User-Id`, `X-User-Roles`,
   `X-External-Reference`; a **service token** for the route's `audience` (from
   `student360-common`'s `ServiceTokenProvider`) proves the call came through the gateway;
   `X-Request-Id` and W3C `traceparent` are always present.

## Resilience

Every domain route sits behind a Resilience4j circuit breaker with an observable fallback: when
`lms-service` is down the 360° view still answers with academic and financial data and the
engagement panel receives `503 {"title":"Upstream unavailable","section":"engagement"}`. Circuit
states are visible on `/actuator/health` (`circuitBreakers`) and `/actuator/circuitbreakers`.

## Correlation

`CorrelationWebFilter` is the origin of every request id in the platform: it honours a
well-formed incoming `X-Request-Id` or generates one, forwards it, echoes it on the response and
puts it in the Reactor context so JSON log lines carry `requestId` even inside reactive chains.

## Run locally

```bash
cd ../student360-infra && make up && make run-auth-service   # in one terminal
cd ../student360-infra && make run-gateway                     # in another
```

CORS is limited to `FRONTEND_ORIGIN` (default `http://localhost:5173`), credentials allowed for
the refresh cookie.

## Verify

```
mvn verify   # format, style, WebTestClient tests against a MockWebServer downstream
```

`GatewayIntegrationTest` is phase gate 2: no token → 401; student on an advisor route → 403;
identity rewritten and service token attached (validated with the shared secret); SSO routes
untouched; dead LMS → fallback body and circuit `OPEN`; CORS only from the SPA origin.
