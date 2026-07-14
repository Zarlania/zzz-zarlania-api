package com.zarlania.api.config;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS configuration for browser clients. Allowed origins are an explicit allowlist sourced from
 * {@code zarlania.cors.allowed-origins} (overridable per environment), never a wildcard. Exposed as
 * a {@link CorsConfigurationSource} bean so Spring Security's CORS filter applies it to every
 * request (including preflights that would otherwise hit the auth chain); the previous {@code
 * WebMvcConfigurer#addCorsMappings} approach is retired with the introduction of the security
 * filter chain (issue #75).
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig {

  private final CorsProperties cors;

  WebConfig(CorsProperties cors) {
    this.cors = cors;
  }

  /**
   * The application-wide CORS policy: allowlisted origins, the API's methods, and the headers
   * browser clients send — including {@code Authorization} for bearer tokens.
   *
   * @return the CORS configuration source consumed by the security filter chain
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(cors.allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "Authorization"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
