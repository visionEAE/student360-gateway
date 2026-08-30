package co.edu.icesi.student360.gateway.infrastructure.filter;

import co.edu.icesi.student360.common.logging.Correlation;
import co.edu.icesi.student360.common.logging.MdcKeys;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The origin of every request id in the platform. Honours a well-formed incoming id (so a client
 * can correlate its own retries) or generates one, forwards it downstream, echoes it on the
 * response and places it in the Reactor context for the logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationWebFilter implements WebFilter {

  private static final Logger log = LoggerFactory.getLogger(CorrelationWebFilter.class);
  private static final Pattern ACCEPTED_ID = Pattern.compile("^[A-Za-z0-9._-]{8,128}$");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String requestId =
        resolve(exchange.getRequest().getHeaders().getFirst(Correlation.REQUEST_ID_HEADER));
    ServerHttpRequest request =
        exchange.getRequest().mutate().header(Correlation.REQUEST_ID_HEADER, requestId).build();
    exchange.getResponse().getHeaders().set(Correlation.REQUEST_ID_HEADER, requestId);
    ServerWebExchange correlated = exchange.mutate().request(request).build();
    return chain
        .filter(correlated)
        // One access line per request: the anchor that lets a gateway log line be matched with
        // the downstream service's lines through requestId and traceId.
        .doFinally(
            signal ->
                log.info(
                    "{} {} -> {}",
                    request.getMethod(),
                    request.getPath().value(),
                    correlated.getResponse().getStatusCode()))
        .contextWrite(context -> context.put(MdcKeys.REQUEST_ID, requestId));
  }

  static String resolve(String incoming) {
    return incoming != null && ACCEPTED_ID.matcher(incoming).matches()
        ? incoming
        : UUID.randomUUID().toString();
  }
}
