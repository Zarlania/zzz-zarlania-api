package com.zarlania.api.features.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.features.Feature;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// Controller test through the full stack via MockMvc; rolls back after each method. The canary
// toggle row exists because FeatureToggleSynchronizer ran (and committed) at context startup.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FeatureToggleAdminControllerTest {

  private static final String CANARY = Feature.FEATURE_SERVICE_CANARY.toggleName();
  private static final String BASE = "/api/admin/feature-toggles";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  @Test
  void listContainsTheCanaryToggle() throws Exception {
    mockMvc
        .perform(get(BASE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name == '" + CANARY + "')]").exists());
  }

  @Test
  void getReturnsToggleShape() throws Exception {
    mockMvc
        .perform(get(BASE + "/" + CANARY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(CANARY))
        .andExpect(jsonPath("$.description").isNotEmpty())
        .andExpect(jsonPath("$.percentage").isNumber())
        .andExpect(jsonPath("$.organizationOverrides").isArray());
  }

  @Test
  void getUnknownToggleReturns404ProblemJson() throws Exception {
    mockMvc
        .perform(get(BASE + "/NOT_A_TOGGLE"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("No feature toggle exists with the given name"));
  }

  @Test
  void putUpdatesGlobalPercentage() throws Exception {
    mockMvc
        .perform(
            put(BASE + "/" + CANARY).contentType(MediaType.APPLICATION_JSON).content(body(100)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.percentage").value(100));
  }

  @Test
  void putRejectsOutOfRangePercentage() throws Exception {
    mockMvc
        .perform(
            put(BASE + "/" + CANARY).contentType(MediaType.APPLICATION_JSON).content(body(101)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.percentage").exists());
  }

  @Test
  void putRejectsMissingPercentage() throws Exception {
    mockMvc
        .perform(put(BASE + "/" + CANARY).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.percentage").exists());
  }

  @Test
  void organizationOverrideRoundTrip() throws Exception {
    UUID organizationId = seedOrganization();

    mockMvc
        .perform(
            put(BASE + "/" + CANARY + "/organizations/" + organizationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(10)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.organizationOverrides[0].organizationId").value(organizationId.toString()))
        .andExpect(jsonPath("$.organizationOverrides[0].percentage").value(10));

    mockMvc
        .perform(delete(BASE + "/" + CANARY + "/organizations/" + organizationId))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get(BASE + "/" + CANARY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizationOverrides").isEmpty());
  }

  @Test
  void organizationOverrideForUnknownOrganizationReturns404() throws Exception {
    mockMvc
        .perform(
            put(BASE + "/" + CANARY + "/organizations/" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(10)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("No organization exists with the given id"));
  }

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String body(int percentage) {
    return "{\"percentage\":" + percentage + "}";
  }

  private UUID seedOrganization() {
    User creator = userService.create(unique("e") + "@example.com", unique("u"));
    Organization organization =
        organizationService.createGeneralOrganization(creator.id(), unique("org"));
    return organization.id();
  }
}
