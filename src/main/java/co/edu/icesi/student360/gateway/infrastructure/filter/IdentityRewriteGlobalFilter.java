package co.edu.icesi.student360.gateway.infrastructure.filter;

import co.edu.icesi.student360.common.identity.IdentityHeaders;
import co.edu.icesi.student360.common.security.ServiceTokenProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Identity rewriting. Past this point the user's access token never travels: the downstream service
 * receives the validated identity as headers plus a service token proving the request came through
 * the gateway. Routes without an {@code audience} in their metadata (the SSO) are public and
 * forwarded untouched.
 */
@Component
public class IdentityRewriteGlobalFilter implements GlobalFilter, Ordered {

  static final String AUDIENCE_METADATA = "audience";
  static final String ROLES_CLAIM = "roles";
  static final String REFERENCE_CLAIM = "ref";

  private final ServiceTokenProvider serviceTokens;

  public IdentityRewriteGlobalFilter(ServiceTokenProvider serviceTokens) {
    this.serviceTokens = serviceTokens;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    Optional<String> audience = audienceOf(exchange);
    if (audience.isEmpty()) {
      return chain.filter(exchange);
    }
    return exchange
        .getPrincipal()
        .filter(JwtAuthenticationToken.class::isInstance)
        .map(JwtAuthenticationToken.class::cast)
        .map(JwtAuthenticationToken::getToken)
        .map(jwt -> rewrite(exchange, jwt, audience.get()))
        .defaultIfEmpty(rewrite(exchange, null, audience.get()))
        .flatMap(chain::filter);
  }

  private ServerWebExchange rewrite(ServerWebExchange exchange, Jwt jwt, String audience) {
    ServerHttpRequest request =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  headers.remove(HttpHeaders.AUTHORIZATION);
                  headers.remove(IdentityHeaders.USER_ID);
                  headers.remove(IdentityHeaders.USER_ROLES);
                  headers.remove(IdentityHeaders.EXTERNAL_REFERENCE);
                  headers.setBearerAuth(serviceTokens.tokenFor(audience));
                  if (jwt != null) {
                    headers.set(IdentityHeaders.USER_ID, jwt.getSubject());
                    List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
                    headers.set(
                        IdentityHeaders.USER_ROLES, roles == null ? "" : String.join(",", roles));
                    String reference = jwt.getClaimAsString(REFERENCE_CLAIM);
                    if (reference != null) {
                      headers.set(IdentityHeaders.EXTERNAL_REFERENCE, reference);
                    }
                  }
                })
            .build();
    return exchange.mutate().request(request).build();
  }

  private static Optional<String> audienceOf(ServerWebExchange exchange) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (route == null) {
      return Optional.empty();
    }
    Map<String, Object> metadata = route.getMetadata();
    Object audience = metadata.get(AUDIENCE_METADATA);
    return audience == null ? Optional.empty() : Optional.of(audience.toString());
  }

  @Override
  public int getOrder() {
    // Before the routing filters (which send the request) and after route resolution.
    return Ordered.LOWEST_PRECEDENCE - 100;
  }
}
