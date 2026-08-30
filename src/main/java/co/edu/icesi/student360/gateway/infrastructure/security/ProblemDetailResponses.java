package co.edu.icesi.student360.gateway.infrastructure.security;

import co.edu.icesi.student360.common.logging.Correlation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** RFC 7807 bodies for responses the gateway produces itself (401, 403, 503 fallback). */
public final class ProblemDetailResponses {

  private ProblemDetailResponses() {}

  public static Mono<Void> write(
      ServerWebExchange exchange,
      ObjectMapper objectMapper,
      HttpStatus status,
      String title,
      String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    String requestId = exchange.getResponse().getHeaders().getFirst(Correlation.REQUEST_ID_HEADER);
    if (requestId != null) {
      problem.setProperty("requestId", requestId);
    }
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    byte[] body;
    try {
      body = objectMapper.writeValueAsBytes(problem);
    } catch (JsonProcessingException exception) {
      body = ("{\"status\":" + status.value() + "}").getBytes(StandardCharsets.UTF_8);
    }
    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
    return exchange.getResponse().writeWith(Mono.just(buffer));
  }
}
