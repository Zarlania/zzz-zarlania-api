package com.zarlania.api.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.auth.exception.InvalidRefreshTokenException;
import com.zarlania.api.features.Feature;
import com.zarlania.api.features.service.FeatureToggleAdminService;
import com.zarlania.api.features.service.FeatureToggleSynchronizer;
import com.zarlania.api.identity.service.PasswordCredentialService;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.support.AbstractTransactionalTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

// Runs with NO wrapping test transaction — the same reason RefreshTokenServiceTransactionalTest
// does — so AuthService.refresh() executes in its own transaction and commits-or-rolls-back for
// real. This is the only way to prove, THROUGH AuthService (not just through
// RefreshTokenService directly), that the outer @Transactional boundary on refresh() carries
// the same noRollbackFor = InvalidRefreshTokenException.class as the inner rotate() call: without
// it, the replay-triggered family revocation would be silently undone when the exception
// re-marks the (REQUIRED-propagated, shared) transaction rollback-only as it crosses the outer
// boundary. Because it commits, it's in the serial *TransactionalTest suite and relies on
// truncation (from AbstractTransactionalTest) rather than rollback for isolation.
class AuthServiceTransactionalTest extends AbstractTransactionalTest {

  private static final String PASSWORD = "Str0ng!Pass";

  @Autowired private AuthService authService;
  @Autowired private FeatureToggleAdminService featureToggleAdminService;
  @Autowired private FeatureToggleSynchronizer featureToggleSynchronizer;
  @Autowired private UserService userService;
  @Autowired private PasswordCredentialService passwordCredentialService;
  @Autowired private OrganizationService organizationService;

  // This suite's own truncation listener (see AbstractTransactionalTest) clears every table,
  // including feature_toggles, after each committing test method — and FeatureToggleSynchronizer
  // only seeds toggle rows once at application startup. So a toggle row this test needs may
  // already be gone by the time it runs: reseed it via the same synchronizer service startup
  // uses, rather than assume the startup sync's row still exists.
  private void reseedFeatureToggles() {
    featureToggleSynchronizer.run(new DefaultApplicationArguments());
  }

  @Test
  void replayTriggeredFamilyRevocationSurvivesRejectionThroughAuthServiceRefresh() {
    reseedFeatureToggles();
    featureToggleAdminService.setPercentage(Feature.PASSWORD_LOGIN.toggleName(), 100);
    String unique = UUID.randomUUID().toString().substring(0, 8);
    String email = unique + "@example.com";
    User user = userService.create(email, "u" + unique);
    passwordCredentialService.create(user.id(), PASSWORD);
    organizationService.createPersonalOrganization(user.id(), "u" + unique);

    String originalRefreshToken = authService.login(email, PASSWORD).refreshToken();
    String successorRefreshToken = authService.refresh(originalRefreshToken).refreshToken();

    // Replaying the consumed original through AuthService: rejected...
    assertThatThrownBy(() -> authService.refresh(originalRefreshToken))
        .isInstanceOf(InvalidRefreshTokenException.class);

    // ...and the family revocation that rejection triggered must have COMMITTED despite the
    // exception: the live successor, presented through AuthService, is now unusable too.
    assertThatThrownBy(() -> authService.refresh(successorRefreshToken))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }
}
