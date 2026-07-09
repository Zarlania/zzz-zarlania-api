package com.zarlania.api.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the admin API surface ({@code /api/admin/**}) out of the public OpenAPI document while the
 * app has no authentication: the root {@code /v3/api-docs} (ADR-0003's public contract, read by
 * Swagger UI) is filtered by a customizer, and machine-readable admin/public group documents exist
 * only when {@code zarlania.docs.expose-admin=true} (a development aid). Springdoc lists every
 * registered group in the Swagger UI selector with no way to hide one, which is why the groups are
 * property-gated rather than always-on. Hiding docs is defense-in-depth, not security: the
 * endpoints themselves remain callable until real auth lands.
 */
@Configuration
public class OpenApiVisibilityConfig {

  /** Path prefix of the admin API surface, excluded from public docs. */
  static final String ADMIN_PATH_PREFIX = "/api/admin/";

  /**
   * Strips admin paths from the root (public) document. A plain — not global — customizer applies
   * only to the root document, leaving the property-gated group documents untouched.
   *
   * @return the customizer that removes {@code /api/admin/**} paths
   */
  @Bean
  public OpenApiCustomizer publicDocAdminPathFilter() {
    return openApi -> {
      if (openApi.getPaths() != null) {
        openApi
            .getPaths()
            .keySet()
            .removeIf(
                path ->
                    path.startsWith(ADMIN_PATH_PREFIX)
                        || path.equals(
                            ADMIN_PATH_PREFIX.substring(0, ADMIN_PATH_PREFIX.length() - 1)));
      }
    };
  }

  /**
   * Development-only admin group document at {@code /v3/api-docs/admin}.
   *
   * @return the admin group definition
   */
  @Bean
  @ConditionalOnProperty(name = "zarlania.docs.expose-admin", havingValue = "true")
  public GroupedOpenApi adminOpenApi() {
    return GroupedOpenApi.builder().group("admin").pathsToMatch("/api/admin/**").build();
  }

  /**
   * Development-only public group document at {@code /v3/api-docs/public}, so the Swagger UI
   * selector offers both surfaces when admin docs are exposed.
   *
   * @return the public group definition
   */
  @Bean
  @ConditionalOnProperty(name = "zarlania.docs.expose-admin", havingValue = "true")
  public GroupedOpenApi publicOpenApi() {
    return GroupedOpenApi.builder().group("public").pathsToExclude("/api/admin/**").build();
  }
}
