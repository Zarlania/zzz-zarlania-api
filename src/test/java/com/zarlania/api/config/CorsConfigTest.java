package com.zarlania.api.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// @AutoConfigureMockMvc (rather than a bare WebApplicationContext-backed MockMvc) so the
// autowired MockMvc runs requests through the registered springSecurityFilterChain — CORS now
// lives in Spring Security's CORS filter (issue #75), not the MVC handler-mapping layer.
@SpringBootTest(properties = "zarlania.cors.allowed-origins=https://zarlania.com")
@AutoConfigureMockMvc
class CorsConfigTest {

  @Autowired private MockMvc mockMvc;

  private MockMvc mockMvc() {
    return mockMvc;
  }

  @Test
  void allowedOriginGetsCorsHeader() throws Exception {
    mockMvc()
        .perform(get("/v3/api-docs").header("Origin", "https://zarlania.com"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://zarlania.com"));
  }

  @Test
  void disallowedOriginIsRejected() throws Exception {
    mockMvc()
        .perform(
            options("/v3/api-docs")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  void allowedOriginPreflightSucceeds() throws Exception {
    mockMvc()
        .perform(
            options("/v3/api-docs")
                .header("Origin", "https://zarlania.com")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://zarlania.com"));
  }

  @Test
  void allowedOriginPostPreflightSucceeds() throws Exception {
    // Model the real browser preflight for the JSON POST /accounts flow: it sends
    // Access-Control-Request-Headers: content-type, so assert that header is allowed back too —
    // otherwise the test could pass while the actual cross-origin request fails at preflight.
    mockMvc()
        .perform(
            options("/accounts")
                .header("Origin", "https://zarlania.com")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://zarlania.com"))
        .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Headers", containsStringIgnoringCase("content-type")));
  }

  @Test
  void allowedOriginPutPreflightSucceeds() throws Exception {
    mockMvc()
        .perform(
            options("/api/admin/feature-toggles/feature-service-canary")
                .header("Origin", "https://zarlania.com")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://zarlania.com"))
        .andExpect(header().string("Access-Control-Allow-Methods", containsString("PUT")))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Headers", containsStringIgnoringCase("content-type")));
  }

  @Test
  void allowedOriginDeletePreflightSucceeds() throws Exception {
    mockMvc()
        .perform(
            options(
                    "/api/admin/feature-toggles/feature-service-canary/organizations/"
                        + "11111111-1111-1111-1111-111111111111")
                .header("Origin", "https://zarlania.com")
                .header("Access-Control-Request-Method", "DELETE"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://zarlania.com"))
        .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")));
  }

  @Test
  void preflightAllowsAuthorizationHeader() throws Exception {
    mockMvc()
        .perform(
            options("/api/admin/feature-toggles")
                .header("Origin", "https://zarlania.com")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "Authorization"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Headers", containsStringIgnoringCase("authorization")));
  }
}
