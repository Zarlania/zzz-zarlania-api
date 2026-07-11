package com.zarlania.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.features.service.FeatureToggleAdminService;
import com.zarlania.api.identity.dto.Account;
import com.zarlania.api.identity.repository.PasswordCredentialRepository;
import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
import com.zarlania.api.users.exception.UsernameAlreadyExistsException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// Cross-domain orchestration over a real database, rolled back per method. These cases only need to
// observe behavior and responses (creation, duplicate rejection), not a real commit, so they run in
// the test's transaction and stay parallel-safe. The atomicity case — which must observe a real
// rollback — lives in IdentityServiceTransactionalTest instead (see its comment).
@SpringBootTest
@Transactional
class IdentityServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private IdentityService identityService;
  @Autowired private OrganizationService organizationService;
  @Autowired private FeatureToggleAdminService featureToggleAdminService;
  @Autowired private PasswordCredentialRepository passwordCredentialRepository;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void createAccountCreatesUserAndPersonalOrgNamedAfterUsername() {
    String username = unique("user");
    String email = username + "@example.com";

    Account account = identityService.createAccount(email, username, null);

    assertThat(account.user().id()).isNotNull();
    assertThat(account.user().email()).isEqualTo(email);
    assertThat(account.user().username()).isEqualTo(username);
    assertThat(account.personalOrganization().name()).isEqualTo(username);
    assertThat(account.personalOrganization().type()).isEqualTo(OrganizationType.PERSONAL);

    List<Membership> memberships =
        organizationService.findMemberships(account.personalOrganization().id());
    assertThat(memberships)
        .singleElement()
        .satisfies(
            membership -> {
              assertThat(membership.userId()).isEqualTo(account.user().id());
              assertThat(membership.role()).isEqualTo(MembershipRole.OWNER);
            });
  }

  @Test
  void createAccountRejectsDuplicateEmail() {
    String email = unique("dupemail") + "@example.com";
    identityService.createAccount(email, unique("name"), null);

    assertThatThrownBy(() -> identityService.createAccount(email, unique("name"), null))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void createAccountRejectsDuplicateUsername() {
    String username = unique("dupname");
    identityService.createAccount(unique("e") + "@example.com", username, null);

    assertThatThrownBy(
            () -> identityService.createAccount(unique("e") + "@example.com", username, null))
        .isInstanceOf(UsernameAlreadyExistsException.class);
  }

  @Test
  void storesPasswordCredentialWhenToggleEnabled() {
    featureToggleAdminService.setPercentage("password-accounts", 100);
    String email = unique("pw") + "@example.com";

    var account = identityService.createAccount(email, unique("pw"), "Str0ng!Pass");

    assertThat(passwordCredentialRepository.findByUserId(account.user().id())).isPresent();
  }

  @Test
  void storesNoCredentialWhenToggleDisabled() {
    // Toggle is off by default; a supplied password is ignored.
    String email = unique("np") + "@example.com";

    var account = identityService.createAccount(email, unique("np"), "Str0ng!Pass");

    assertThat(passwordCredentialRepository.findByUserId(account.user().id())).isEmpty();
  }
}
