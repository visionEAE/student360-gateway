package co.edu.icesi.student360.gateway.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Coarse authorization: which roles may reach which route family. The fine-grained question — may
 * <em>this</em> student or advisor see <em>that</em> student — is answered downstream by the
 * service that owns the data.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

  static final String ROLES_CLAIM = "roles";
  static final String STUDENT = "STUDENT";
  static final String ADVISOR = "ADVISOR";
  static final String ADMIN = "ADMIN";

  /**
   * CORS is enforced by Spring Security's filter (which runs before authentication, so preflights
   * never need a token) and restricted to the SPA origin. Credentials are allowed for the refresh
   * cookie.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(
      @Value("${student360.gateway.allowed-origins}") List<String> allowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setExposedHeaders(List.of("X-Request-Id"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, ObjectMapper objectMapper) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(ROLES_CLAIM);
    authorities.setAuthorityPrefix("ROLE_");
    converter.setJwtGrantedAuthoritiesConverter(authorities);

    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .cors(cors -> {})
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/api/auth/**", "/actuator/health/**", "/fallback/**")
                    .permitAll()
                    .pathMatchers("/api/core/**", "/api/lms/**")
                    .hasAnyRole(STUDENT, ADVISOR, ADMIN)
                    .pathMatchers("/api/support/students/**")
                    .hasAnyRole(STUDENT, ADVISOR)
                    .pathMatchers("/api/support/advisors/**")
                    .hasAnyRole(ADVISOR, ADMIN)
                    .pathMatchers("/api/network/**")
                    .hasAnyRole(STUDENT, ADVISOR, ADMIN)
                    .pathMatchers("/actuator/**")
                    .hasRole(ADMIN)
                    .anyExchange()
                    .denyAll())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(
                        jwt ->
                            jwt.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(converter)))
                    .authenticationEntryPoint(
                        (exchange, exception) ->
                            ProblemDetailResponses.write(
                                exchange,
                                objectMapper,
                                HttpStatus.UNAUTHORIZED,
                                "Authentication failed",
                                "Missing or invalid access token"))
                    .accessDeniedHandler(
                        (exchange, exception) ->
                            ProblemDetailResponses.write(
                                exchange,
                                objectMapper,
                                HttpStatus.FORBIDDEN,
                                "Access denied",
                                "Role not allowed on this route")))
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(
                        (exchange, exception) ->
                            ProblemDetailResponses.write(
                                exchange,
                                objectMapper,
                                HttpStatus.UNAUTHORIZED,
                                "Authentication failed",
                                "Missing or invalid access token"))
                    .accessDeniedHandler(
                        (exchange, exception) ->
                            ProblemDetailResponses.write(
                                exchange,
                                objectMapper,
                                HttpStatus.FORBIDDEN,
                                "Access denied",
                                "Role not allowed on this route")))
        .build();
  }
}
