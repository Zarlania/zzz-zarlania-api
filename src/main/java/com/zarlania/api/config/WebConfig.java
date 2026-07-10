package com.zarlania.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for browser clients. Allowed origins are an explicit allowlist sourced from
 * {@code zarlania.cors.allowed-origins} (overridable per environment), never a wildcard.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig implements WebMvcConfigurer {

  private final CorsProperties cors;

  WebConfig(CorsProperties cors) {
    this.cors = cors;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    // NOTE: FindSecBugs' CorsRegistryCORSDetector cannot analyze this method — it NPEs on the
    // config-sourced String[] of origins (see issue #23), so PERMISSIVE_CORS is not statically
    // checked here. CORS behavior is guarded by CorsConfigTest (allowed/disallowed origin +
    // preflight) instead. Methods/headers are scoped to the current API surface: GET reads plus
    // POST/PUT/DELETE writes (e.g. POST /accounts, and the PUT/DELETE admin feature-toggle
    // endpoints under /api/admin/feature-toggles).
    registry
        .addMapping("/**")
        .allowedOrigins(cors.allowedOrigins().toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Content-Type", "Accept");
  }
}
