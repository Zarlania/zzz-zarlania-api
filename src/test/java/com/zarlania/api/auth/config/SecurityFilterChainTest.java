package com.zarlania.api.auth.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.features.service.FeatureToggleAdminService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// e2e proof of the deny-by-default posture: with auth-enforcement OFF everything behaves
// as before this change; with it ON, non-permit-listed paths demand a valid bearer JWT
// while the public permit-list stays open. Token-accepted paths are covered in
// JwtIssuerTest (Task 5) once an issuer exists.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityFilterChainTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private FeatureToggleAdminService featureToggleAdminService;

  private void enforcementOn() {
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
  }

  @Test
  void adminEndpointStaysOpenWhileEnforcementOff() throws Exception {
    mockMvc.perform(get("/api/admin/feature-toggles")).andExpect(status().isOk());
  }

  @Test
  void adminEndpointRequires401WithBearerChallengeWhenEnforcementOn() throws Exception {
    enforcementOn();
    mockMvc
        .perform(get("/api/admin/feature-toggles"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
  }

  @Test
  void garbageTokenIsRejectedWhenEnforcementOn() throws Exception {
    enforcementOn();
    mockMvc
        .perform(get("/api/admin/feature-toggles").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void signupStaysOpenWhenEnforcementOn() throws Exception {
    enforcementOn();
    String username = "sec" + UUID.randomUUID().toString().substring(0, 8);
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\""
                        + username
                        + "@example.com\",\"username\":\""
                        + username
                        + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void openApiAndHealthStayOpenWhenEnforcementOn() throws Exception {
    enforcementOn();
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
  }

  @Test
  void responsesAreStatelessNoSessionCookieEverIssued() throws Exception {
    // Compensating test for the deliberate csrf().disable(): the API is stateless — no
    // session is created, so no cookie exists for a cross-site request to ride.
    enforcementOn();
    mockMvc
        .perform(get("/api/admin/feature-toggles"))
        .andExpect(header().doesNotExist("Set-Cookie"));
    mockMvc.perform(get("/v3/api-docs")).andExpect(header().doesNotExist("Set-Cookie"));
  }
}
