package com.zarlania.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// The public OpenAPI document (the endpoint contract of record, ADR-0003) must document
// the bearer scheme and the /auth surface while still excluding /api/admin/** (ADR-0015).
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSecuritySchemeTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void publicDocumentCarriesBearerSchemeAndAuthEndpointsButNoAdminPaths() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.paths['/auth/login']").exists())
        .andExpect(jsonPath("$.paths['/auth/refresh']").exists())
        .andExpect(jsonPath("$.paths['/auth/logout']").exists())
        .andExpect(jsonPath("$.paths['/api/admin/feature-toggles']").doesNotExist());
  }
}
