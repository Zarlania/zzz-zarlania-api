package com.zarlania.api.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The stateless, deny-by-default security chain (see the org-scoped JWT auth ADR). An explicit
 * permit-list covers the public surface — signup, the POST /auth/login, /auth/refresh, and
 * /auth/logout token endpoints, the public OpenAPI docs (ADR-0003), and actuator health/info
 * (ADR-0002); every other path, present or future — including any other method or future path under
 * /auth/** — is guarded by {@link ToggleAwareAuthorizationManager}, so new endpoints are born
 * protected and are opted <em>out</em> of auth, never bolted on.
 *
 * <p>CSRF protection is disabled deliberately: the API is a pure bearer-token surface with {@code
 * STATELESS} session policy — no session, no cookie, nothing for a cross-site request to ride.
 * {@code SecurityFilterChainTest} proves no Set-Cookie is ever issued.
 */
@Configuration
@EnableConfigurationProperties(AuthTokenProperties.class)
public class SecurityConfig {

  /**
   * Builds the single API security filter chain.
   *
   * @param http the builder
   * @param enforcement the toggle-aware rule for non-permit-listed paths
   * @return the chain
   * @throws IllegalStateException if the builder fails; wraps {@link HttpSecurity#build()}'s
   *     declared {@code Exception} so this method's own contract stays specific (SpotBugs
   *     THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION) — Spring wraps either form identically in a
   *     {@code BeanCreationException} at context startup
   */
  @Bean
  public SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http, ToggleAwareAuthorizationManager enforcement) {
    try {
      http.sessionManagement(
              session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .csrf(csrf -> csrf.disable())
          .cors(Customizer.withDefaults())
          .authorizeHttpRequests(
              auth ->
                  // Error (ASYNC-forwarded) dispatches carry the original request's outcome, not
                  // a fresh caller-driven request: an unhandled exception on a permit-listed
                  // anonymous endpoint forwards to /error as an ERROR dispatch, which must not be
                  // authenticated — doing so would turn a 500 into a masking 401. MockMvc cannot
                  // synthesize a true ERROR dispatch, so /error is also permit-listed as a REQUEST
                  // path; both matchers are required and complementary (dispatcherTypeMatchers
                  // covers the real ERROR forward Boot performs in production, requestMatchers
                  // covers /error reached directly, e.g. in tests).
                  auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                      .permitAll()
                      .requestMatchers("/error")
                      .permitAll()
                      .requestMatchers(HttpMethod.OPTIONS, "/**")
                      .permitAll()
                      .requestMatchers(HttpMethod.POST, "/accounts")
                      .permitAll()
                      .requestMatchers(
                          HttpMethod.POST, "/auth/login", "/auth/refresh", "/auth/logout")
                      .permitAll()
                      .requestMatchers(
                          "/v3/api-docs",
                          "/v3/api-docs/**",
                          "/v3/api-docs.yaml",
                          "/swagger-ui/**",
                          "/swagger-ui.html")
                      .permitAll()
                      .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                      .permitAll()
                      .anyRequest()
                      .access(enforcement))
          .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
      return http.build();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build the API security filter chain", e);
    }
  }

  /**
   * Decodes and validates incoming HS256 bearer tokens with the shared signing secret.
   *
   * @param properties the auth token configuration
   * @return the decoder
   */
  @Bean
  public JwtDecoder jwtDecoder(AuthTokenProperties properties) {
    return NimbusJwtDecoder.withSecretKey(secretKey(properties))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }

  /**
   * Signs access tokens with the shared HS256 secret. Consumed by {@code JwtIssuer}.
   *
   * @param properties the auth token configuration
   * @return the encoder
   */
  @Bean
  public JwtEncoder jwtEncoder(AuthTokenProperties properties) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
  }

  private static SecretKeySpec secretKey(AuthTokenProperties properties) {
    return new SecretKeySpec(
        properties.jwt().signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }
}
