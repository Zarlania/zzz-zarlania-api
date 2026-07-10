package com.zarlania.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
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

  /** Ant-style pattern matching every admin path, for springdoc group include/exclude. */
  static final String ADMIN_PATH_PATTERN = ADMIN_PATH_PREFIX + "**";

  private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

  /**
   * Strips admin paths from the root (public) document, then prunes any component schemas that only
   * those removed operations referenced — otherwise admin-only DTOs (the toggle request/response
   * shapes) would linger in {@code components} of the public document even though no operation
   * mentions them. A plain — not global — customizer applies only to the root document, leaving the
   * property-gated group documents untouched.
   *
   * @return the customizer that removes {@code /api/admin/**} paths and their orphaned schemas
   */
  @Bean
  public OpenApiCustomizer publicDocAdminPathFilter() {
    return openApi -> {
      if (openApi.getPaths() == null) {
        return;
      }
      openApi.getPaths().keySet().removeIf(OpenApiVisibilityConfig::isAdminPath);
      pruneUnreferencedSchemas(openApi);
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
    return GroupedOpenApi.builder().group("admin").pathsToMatch(ADMIN_PATH_PATTERN).build();
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
    return GroupedOpenApi.builder().group("public").pathsToExclude(ADMIN_PATH_PATTERN).build();
  }

  private static boolean isAdminPath(String path) {
    return path.startsWith(ADMIN_PATH_PREFIX)
        || path.equals(ADMIN_PATH_PREFIX.substring(0, ADMIN_PATH_PREFIX.length() - 1));
  }

  /**
   * Removes every component schema unreachable from the remaining paths. Reachability is computed
   * transitively (a kept schema may reference others), so shared schemas that any surviving public
   * operation still uses are preserved.
   */
  private static void pruneUnreferencedSchemas(OpenAPI openApi) {
    if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
      return;
    }
    JsonNode schemas = Json.mapper().valueToTree(openApi.getComponents().getSchemas());
    Set<String> reachable = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>(referencedSchemas(toJson(openApi.getPaths())));
    while (!pending.isEmpty()) {
      String name = pending.poll();
      if (!reachable.add(name)) {
        continue;
      }
      JsonNode schema = schemas.get(name);
      if (schema != null) {
        pending.addAll(referencedSchemas(schema));
      }
    }
    openApi.getComponents().getSchemas().keySet().retainAll(reachable);
  }

  private static JsonNode toJson(Object node) {
    return Json.mapper().valueToTree(node);
  }

  private static Set<String> referencedSchemas(JsonNode node) {
    Set<String> names = new HashSet<>();
    collectSchemaRefs(node, names);
    return names;
  }

  private static void collectSchemaRefs(JsonNode node, Set<String> names) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      JsonNode ref = node.get("$ref");
      if (ref != null && ref.isTextual() && ref.asText().startsWith(SCHEMA_REF_PREFIX)) {
        names.add(ref.asText().substring(SCHEMA_REF_PREFIX.length()));
      }
      node.forEach(child -> collectSchemaRefs(child, names));
    } else if (node.isArray()) {
      node.forEach(child -> collectSchemaRefs(child, names));
    }
  }
}
