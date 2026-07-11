package com.zarlania.api.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.features.service.FeatureToggleAdminService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// e2e for the /auth surface: the full login → protected-admin-call → refresh →
// reuse-tripwire → logout loop, plus toggle gating and the anti-enumeration 401 contract.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

  private static final String PASSWORD = "Str0ng!Pass";

  @Autowired private MockMvc mockMvc;
  @Autowired private FeatureToggleAdminService featureToggleAdminService;
  @Autowired private ObjectMapper objectMapper;

  private String email;

  @BeforeEach
  void createPasswordAccountAndEnableLogin() throws Exception {
    featureToggleAdminService.setPercentage("password-accounts", 100);
    featureToggleAdminService.setPercentage("password-login", 100);
    String username = "auth" + UUID.randomUUID().toString().substring(0, 8);
    email = username + "@example.com";
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\""
                        + email
                        + "\",\"username\":\""
                        + username
                        + "\",\"password\":\""
                        + PASSWORD
                        + "\"}"))
        .andExpect(status().isCreated());
  }

  private MvcResult login(String loginEmail, String password) throws Exception {
    return mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + loginEmail + "\",\"password\":\"" + password + "\"}"))
        .andReturn();
  }

  private JsonNode loginOk() throws Exception {
    MvcResult result = login(email, PASSWORD);
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void loginReturnsTokenPair() throws Exception {
    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.expiresInSeconds").value(900))
        .andExpect(jsonPath("$.refreshToken").isNotEmpty());
  }

  @Test
  void loginFailuresAreIndistinguishable() throws Exception {
    MvcResult wrongPassword = login(email, "Wr0ng!Pass1");
    MvcResult unknownEmail = login("nobody@example.com", PASSWORD);

    org.assertj.core.api.Assertions.assertThat(wrongPassword.getResponse().getStatus())
        .isEqualTo(401);
    org.assertj.core.api.Assertions.assertThat(unknownEmail.getResponse().getStatus())
        .isEqualTo(401);
    org.assertj.core.api.Assertions.assertThat(wrongPassword.getResponse().getContentAsString())
        .isEqualTo(unknownEmail.getResponse().getContentAsString());
  }

  @Test
  void loginTokenOpensEnforcedAdminEndpoint() throws Exception {
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
    String accessToken = loginOk().get("accessToken").asText();

    mockMvc.perform(get("/api/admin/feature-toggles")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/admin/feature-toggles").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk());
  }

  @Test
  void refreshRotatesTheTokenAndReplayTripsTheFamily() throws Exception {
    String refreshToken = loginOk().get("refreshToken").asText();

    MvcResult rotated =
        mockMvc
            .perform(
                post("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andReturn();
    String successor =
        objectMapper
            .readTree(rotated.getResponse().getContentAsString())
            .get("refreshToken")
            .asText();

    // Replaying the consumed token: 401, and the family (successor included) dies.
    mockMvc
        .perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + successor + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Invalid or expired refresh token"));
  }

  @Test
  void logoutRevokesTheRefreshTokenAndIsIdempotent() throws Exception {
    String refreshToken = loginOk().get("refreshToken").asText();
    String logoutBody = "{\"refreshToken\":\"" + refreshToken + "\"}";

    mockMvc
        .perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(logoutBody))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(logoutBody))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(logoutBody))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authSurfaceIs404WhileToggleOff() throws Exception {
    featureToggleAdminService.setPercentage("password-login", 0);
    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"x\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"x\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void blankFieldsAre400() throws Exception {
    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\" \",\"password\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.email").exists())
        .andExpect(jsonPath("$.errors.password").exists());
  }

  @Test
  void responsesNeverEchoThePassword() throws Exception {
    MvcResult result = login(email, PASSWORD);
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
        .doesNotContain(PASSWORD);
  }
}
