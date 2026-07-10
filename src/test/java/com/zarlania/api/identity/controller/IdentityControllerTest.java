package com.zarlania.api.identity.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

// Controller test through the full stack via MockMvc: asserts the request/response contract and
// middleware (validation, the global exception handler) for POST /accounts. It runs in the test's
// own transaction and rolls back after each method, so it stays fast, isolated, and parallel-
// friendly. Real-port HTTP fidelity, if ever needed, belongs in a separate snapshot suite.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class IdentityControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  @Autowired
  private com.zarlania.api.features.service.FeatureToggleAdminService featureToggleAdminService;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String body(String email, String username) {
    return "{\"email\":\"" + email + "\",\"username\":\"" + username + "\"}";
  }

  private static String bodyWithPassword(String email, String username, String password) {
    return "{\"email\":\""
        + email
        + "\",\"username\":\""
        + username
        + "\",\"password\":\""
        + password
        + "\"}";
  }

  @Test
  void createAccountReturns201WithUserAndPersonalOrg() throws Exception {
    String username = unique("u");
    String email = username + "@example.com";

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, username)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.id").exists())
        .andExpect(jsonPath("$.user.email").value(email))
        .andExpect(jsonPath("$.user.username").value(username))
        .andExpect(jsonPath("$.personalOrganization.name").value(username))
        .andExpect(jsonPath("$.personalOrganization.type").value("PERSONAL"));
  }

  @Test
  void createAccountReturns400ForBlankUsername() throws Exception {
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("ok@example.com", "  ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.username").exists());
  }

  @Test
  void createAccountReturns400ForMalformedEmail() throws Exception {
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("not-an-email", "ok")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.email").exists());
  }

  @Test
  void createAccountReturns409ForDuplicateEmail() throws Exception {
    String email = unique("dupe") + "@example.com";
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, unique("n"))))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, unique("n"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("An account with this email already exists"));
  }

  @Test
  void createAccountReturns409ForDuplicateUsername() throws Exception {
    String username = unique("dupn");
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(unique("e") + "@example.com", username)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(unique("e") + "@example.com", username)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("This username is already taken"));
  }

  @Test
  void createAccountReturns409WhenUsernameCollidesWithExistingOrgName() throws Exception {
    String collidingName = unique("org");
    User owner = userService.create(unique("o") + "@example.com", unique("o"));
    organizationService.createGeneralOrganization(owner.id(), collidingName);

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(unique("v") + "@example.com", collidingName)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("The requested username is unavailable"));
  }

  @Test
  void ignoresPasswordAndUnknownFieldsWhenToggleOff() throws Exception {
    String username = unique("off");
    String email = username + "@example.com";
    String body =
        "{\"email\":\""
            + email
            + "\",\"username\":\""
            + username
            + "\",\"password\":\"Str0ng!Pass\",\"nickname\":\"ignored\"}";

    mockMvc
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.username").value(username))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.user.password").doesNotExist());
  }

  @Test
  void createsAccountWithPasswordWhenToggleOnAndNeverReturnsIt() throws Exception {
    featureToggleAdminService.setPercentage("password-accounts", 100);
    String username = unique("on");
    String email = username + "@example.com";

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithPassword(email, username, "Str0ng!Pass")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.email").value(email))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.user.password").doesNotExist());
  }

  @Test
  void rejectsMissingPasswordWhenToggleOn() throws Exception {
    featureToggleAdminService.setPercentage("password-accounts", 100);

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(unique("m") + "@example.com", unique("m"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("password is required"));
  }

  @Test
  void rejectsWeakPasswordWhenToggleOn() throws Exception {
    featureToggleAdminService.setPercentage("password-accounts", 100);

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithPassword(unique("w") + "@example.com", unique("w"), "weak")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
  }
}
