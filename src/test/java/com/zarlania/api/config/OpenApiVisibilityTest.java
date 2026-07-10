package com.zarlania.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the admin API surface is absent from public docs and gated behind a property. */
class OpenApiVisibilityTest {

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  class DefaultVisibility {

    @Autowired private MockMvc mockMvc;

    @Test
    void publicDocKeepsItsUrlAndOmitsAdminPaths() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paths['/accounts']").exists())
          .andExpect(content().string(Matchers.not(Matchers.containsString("/api/admin/"))));
    }

    @Test
    void adminGroupDocIsAbsentByDefault() throws Exception {
      mockMvc.perform(get("/v3/api-docs/admin")).andExpect(status().isNotFound());
    }

    @Test
    void publicDocOmitsAdminOnlySchemasButKeepsPublicOnes() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          // admin-only DTOs are pruned once their operations are stripped ...
          .andExpect(jsonPath("$.components.schemas.SetPercentageRequest").doesNotExist())
          .andExpect(jsonPath("$.components.schemas.FeatureToggle").doesNotExist())
          .andExpect(
              jsonPath("$.components.schemas.FeatureToggleOrganizationOverride").doesNotExist())
          // ... while schemas a surviving public operation still references are kept.
          .andExpect(jsonPath("$.components.schemas.CreateAccountRequest").exists())
          .andExpect(jsonPath("$.components.schemas.Account").exists());
    }
  }

  @Nested
  @SpringBootTest(properties = "zarlania.docs.expose-admin=true")
  @AutoConfigureMockMvc
  class ExposedForDevelopment {

    @Autowired private MockMvc mockMvc;

    @Test
    void adminGroupDocServesAdminPaths() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs/admin"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paths['/api/admin/feature-toggles']").exists());
    }

    @Test
    void publicGroupDocOmitsAdminPaths() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs/public"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paths['/accounts']").exists())
          .andExpect(content().string(Matchers.not(Matchers.containsString("/api/admin/"))));
    }

    @Test
    void rootDocStillOmitsAdminPaths() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andExpect(content().string(Matchers.not(Matchers.containsString("/api/admin/"))));
    }
  }
}
