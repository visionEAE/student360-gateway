package co.edu.icesi.student360.gateway.api;

import co.edu.icesi.student360.common.logging.Correlation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Observable degradation. When a source service is down or slow, the 360° view still answers with
 * what the other sources provide and marks this section as unavailable, instead of failing as a
 * whole. The {@code section} tells the SPA which panel to grey out.
 */
@RestController
public class FallbackController {

  @RequestMapping("/fallback/{section}")
  public ResponseEntity<ProblemDetail> fallback(
      @PathVariable String section, ServerWebExchange exchange) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "The " + section + " source is temporarily unavailable");
    problem.setTitle("Upstream unavailable");
    problem.setProperty("section", section);
    String requestId = exchange.getResponse().getHeaders().getFirst(Correlation.REQUEST_ID_HEADER);
    if (requestId != null) {
      problem.setProperty("requestId", requestId);
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
  }
}
