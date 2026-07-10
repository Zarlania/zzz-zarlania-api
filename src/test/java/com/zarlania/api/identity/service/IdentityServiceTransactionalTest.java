package com.zarlania.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.service.FeatureToggleAdminService;
import com.zarlania.api.features.service.FeatureToggleSynchronizer;
import com.zarlania.api.organizations.exception.OrganizationNameAlreadyExistsException;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.support.AbstractTransactionalTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

// Runs with NO wrapping test transaction so createAccount executes in its own transaction and
// commits-or-rolls-back for real — the only way to observe that a failed personal-org step actually
// undid the user insert. A wrapping test transaction would make the inner @Transactional merely
// join
// it, so the "user is gone" post-condition would still see the row. Because it therefore commits,
// it's in the serial *TransactionalTest suite and relies on truncation (from
// AbstractTransactionalTest)
// rather than rollback for isolation.
class IdentityServiceTransactionalTest extends AbstractTransactionalTest {

  @Autowired private IdentityService identityService;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;
  @Autowired private FeatureToggleAdminService featureToggleAdminService;
  @Autowired private FeatureToggleSynchronizer featureToggleSynchronizer;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  // This suite's own truncation listener (see AbstractTransactionalTest) clears every table,
  // including feature_toggles, after each committing test method — and FeatureToggleSynchronizer
  // only seeds toggle rows once at application startup. So by the time this class's second test
  // runs, the row the first test's cleanup wiped is gone and never comes back on its own: this test
  // must (re)seed it itself, via the same synchronizer service startup uses, rather than assume the
  // startup sync's row still exists.
  private void reseedFeatureToggles() {
    featureToggleSynchronizer.run(new DefaultApplicationArguments());
  }

  @Test
  void createAccountRollsBackUserWhenPersonalOrgNameCollides() {
    // Arrange: a general org whose name will collide with the next account's username. This must be
    // committed so createAccount's own transaction can see it.
    String collidingName = unique("collide");
    User owner = userService.create(unique("owner") + "@example.com", unique("owner"));
    organizationService.createGeneralOrganization(owner.id(), collidingName);

    String victimEmail = unique("victim") + "@example.com";

    assertThatThrownBy(() -> identityService.createAccount(victimEmail, collidingName, null))
        .isInstanceOf(OrganizationNameAlreadyExistsException.class);

    // The user insert must have rolled back together with the failed org creation.
    assertThat(userService.findByEmail(victimEmail)).isEmpty();
  }

  @Test
  void rejectsInvalidPasswordWhenToggleEnabledAndCreatesNoUser() {
    // Same real-commit-or-rollback rationale as above: only a non-wrapped transaction can observe
    // that the whole account (including the user insert) rolled back, this time because the
    // credential step failed password validation rather than because the org name collided.
    reseedFeatureToggles();
    featureToggleAdminService.setPercentage(Feature.PASSWORD_ACCOUNTS.toggleName(), 100);
    String email = unique("bad") + "@example.com";

    assertThatThrownBy(() -> identityService.createAccount(email, unique("bad"), "weak"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(userService.findByEmail(email)).isEmpty();
  }
}
