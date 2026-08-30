package co.edu.icesi.student360.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.icesi.student360.common.identity.IdentityHeaders;
import co.edu.icesi.student360.common.logging.Correlation;
import co.edu.icesi.student360.common.security.ServiceIdentity;
import co.edu.icesi.student360.common.security.local.LocalServiceTokenValidator;
import co.edu.icesi.student360.gateway.support.TestJwtConfiguration;
import co.edu.icesi.student360.gateway.support.TestTokens;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Phase gate 2: no token → 401; wrong role for the route → 403; right role → identity rewritten and
 * a service token attached; SSO routes untouched; a dead source → observable fallback and an open
 * circuit; CORS limited to the SPA origin.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "SERVICE_TOKEN_SECRET=" + GatewayIntegrationTest.SECRET,
      "resilience4j.timelimiter.configs.default.timeout-duration=2s"
    })
@Import(TestJwtConfiguration.class)
// Boot disables tracing in tests by default; the traceparent assertion needs it on.
@AutoConfigureObservability
class GatewayIntegrationTest {

  static final String SECRET = "0123456789abcdef0123456789abcdef-test-only";
  static final MockWebServer DOWNSTREAM = new MockWebServer();
  private static int deadPort;

  @Autowired private WebTestClient client;
  @Autowired private TestTokens tokens;
  @Autowired private CircuitBreakerRegistry circuitBreakers;
  private int requestsBefore;

  @BeforeAll
  static void startDownstream() throws IOException {
    DOWNSTREAM.start();
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    }
  }

  @AfterAll
  static void stopDownstream() throws IOException {
    DOWNSTREAM.shutdown();
  }

  @org.junit.jupiter.api.BeforeEach
  void baseline() {
    requestsBefore = DOWNSTREAM.getRequestCount();
  }

  @DynamicPropertySource
  static void routeToMocks(DynamicPropertyRegistry registry) {
    registry.add("AUTH_SERVICE_URL", () -> DOWNSTREAM.url("/").toString());
    registry.add("CORE_SERVICE_URL", () -> DOWNSTREAM.url("/").toString());
    registry.add("SUPPORT_SERVICE_URL", () -> DOWNSTREAM.url("/").toString());
    registry.add("NETWORK_SERVICE_URL", () -> DOWNSTREAM.url("/").toString());
    registry.add("LMS_SERVICE_URL", () -> "http://localhost:" + deadPort);
  }

  @Test
  void shouldRejectRequestWithoutTokenBeforeRouting() {
    client
        .get()
        .uri("/api/core/students/S-1001")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .exists(Correlation.REQUEST_ID_HEADER)
        .expectBody()
        .jsonPath("$.title")
        .isEqualTo("Authentication failed")
        .jsonPath("$.requestId")
        .isNotEmpty();
    assertThat(DOWNSTREAM.getRequestCount() - requestsBefore).isZero();
  }

  @Test
  void shouldRejectStudentOnAdvisorRoute() {
    String studentToken = tokens.forUser(UUID.randomUUID(), List.of("STUDENT"), "S-1001");

    client
        .get()
        .uri("/api/support/advisors/me/alerts")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.title")
        .isEqualTo("Access denied");
    assertThat(DOWNSTREAM.getRequestCount() - requestsBefore).isZero();
  }

  @Test
  void shouldAllowAStudentOnTheSupportNetworkRouteWithTheRightAudience() throws Exception {
    UUID userId = UUID.randomUUID();
    String studentToken = tokens.forUser(userId, List.of("STUDENT"), "S-1001");
    DOWNSTREAM.enqueue(
        new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

    client
        .get()
        .uri("/api/network/students/S-1001/support-network")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
        .exchange()
        .expectStatus()
        .isOk();

    RecordedRequest forwarded = DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
    assertThat(forwarded).isNotNull();
    assertThat(forwarded.getHeader(IdentityHeaders.EXTERNAL_REFERENCE)).isEqualTo("S-1001");
    String authorization = forwarded.getHeader(HttpHeaders.AUTHORIZATION);
    ServiceIdentity caller =
        new LocalServiceTokenValidator(
                "network-service", SECRET.getBytes(StandardCharsets.UTF_8), Clock.systemUTC())
            .validate(authorization.substring("Bearer ".length()));
    assertThat(caller.issuer()).isEqualTo("gateway");
  }

  @Test
  void shouldNotDuplicateTheRequestIdWhenTheDownstreamEchoesItToo() throws Exception {
    // Every real service (student360-common's CorrelationFilter) echoes X-Request-Id on its own
    // response too, using the same id the gateway forwarded — reproduce that here, since a mock
    // response that never sets the header (as the other tests do) can't catch the gateway
    // re-adding a second, identical copy on top of the one it merges in from downstream.
    String studentToken = tokens.forUser(UUID.randomUUID(), List.of("STUDENT"), "S-1001");
    DOWNSTREAM.enqueue(
        new MockResponse()
            .setBody("[]")
            .addHeader("Content-Type", "application/json")
            .addHeader(Correlation.REQUEST_ID_HEADER, "demo-request-dedupe"));

    client
        .get()
        .uri("/api/network/students/S-1001/support-network")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
        .header(Correlation.REQUEST_ID_HEADER, "demo-request-dedupe")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(Correlation.REQUEST_ID_HEADER, "demo-request-dedupe");

    // DOWNSTREAM's recorded-request queue is shared by the whole test class: leaving this
    // request undrained would make a later test's takeRequest() pop this one instead of its own.
    assertThat(DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
  }

  @Test
  void shouldRewriteIdentityAndAttachServiceTokenForDomainRoutes() throws Exception {
    UUID userId = UUID.randomUUID();
    String studentToken = tokens.forUser(userId, List.of("STUDENT"), "S-1001");
    DOWNSTREAM.enqueue(
        new MockResponse().setBody("{\"ok\":true}").addHeader("Content-Type", "application/json"));

    client
        .get()
        .uri("/api/core/students/S-1001/financial-status")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
        .header(Correlation.REQUEST_ID_HEADER, "demo-request-0001")
        .header(IdentityHeaders.USER_ID, "spoofed-by-client")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(Correlation.REQUEST_ID_HEADER, "demo-request-0001");

    RecordedRequest forwarded = DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
    assertThat(forwarded)
        .as("downstream requests seen: %d", DOWNSTREAM.getRequestCount() - requestsBefore)
        .isNotNull();
    assertThat(forwarded.getPath()).isEqualTo("/api/core/students/S-1001/financial-status");
    assertThat(forwarded.getHeader(IdentityHeaders.USER_ID)).isEqualTo(userId.toString());
    assertThat(forwarded.getHeader(IdentityHeaders.USER_ROLES)).isEqualTo("STUDENT");
    assertThat(forwarded.getHeader(IdentityHeaders.EXTERNAL_REFERENCE)).isEqualTo("S-1001");
    assertThat(forwarded.getHeader(Correlation.REQUEST_ID_HEADER)).isEqualTo("demo-request-0001");
    assertThat(forwarded.getHeader("traceparent")).as("W3C trace context propagated").isNotNull();

    String authorization = forwarded.getHeader(HttpHeaders.AUTHORIZATION);
    assertThat(authorization).startsWith("Bearer ").doesNotContain(studentToken);
    ServiceIdentity caller =
        new LocalServiceTokenValidator(
                "core-service", SECRET.getBytes(StandardCharsets.UTF_8), Clock.systemUTC())
            .validate(authorization.substring("Bearer ".length()));
    assertThat(caller.issuer()).isEqualTo("gateway");
  }

  @Test
  void shouldForwardSsoRoutesUntouched() throws Exception {
    String token = tokens.forUser(UUID.randomUUID(), List.of("STUDENT"), "S-1001");
    DOWNSTREAM.enqueue(
        new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

    client
        .get()
        .uri("/api/auth/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange()
        .expectStatus()
        .isOk();

    RecordedRequest forwarded = DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
    assertThat(forwarded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
    assertThat(forwarded.getHeader(IdentityHeaders.USER_ID)).isNull();
  }

  @Test
  void shouldFallBackWhenLmsIsDownAndOpenTheCircuit() {
    String token = tokens.forUser(UUID.randomUUID(), List.of("STUDENT"), "S-1001");

    for (int attempt = 0; attempt < 3; attempt++) {
      client
          .get()
          .uri("/api/lms/students/S-1001/signals")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .exchange()
          .expectStatus()
          .isEqualTo(503)
          .expectBody()
          .jsonPath("$.title")
          .isEqualTo("Upstream unavailable")
          .jsonPath("$.section")
          .isEqualTo("engagement")
          .jsonPath("$.requestId")
          .isNotEmpty();
    }

    assertThat(circuitBreakers.circuitBreaker("lms-service").getState())
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void shouldAllowCorsOnlyFromTheSpaOrigin() {
    client
        .options()
        .uri("/api/core/students/S-1001")
        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
        .expectHeader()
        .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");

    client
        .options()
        .uri("/api/core/students/S-1001")
        .header(HttpHeaders.ORIGIN, "http://evil.example")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
        .exchange()
        .expectStatus()
        .isForbidden();
  }
}
