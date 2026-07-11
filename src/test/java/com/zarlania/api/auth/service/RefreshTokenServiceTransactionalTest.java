package com.zarlania.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.auth.exception.InvalidRefreshTokenException;
import com.zarlania.api.auth.repository.RefreshTokenRepository;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.support.AbstractTransactionalTest;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Runs with NO wrapping test transaction so rotate() executes in its own transaction and
// commits-or-rolls-back for real — the only way to observe that the stolen-token tripwire's
// family revocation actually PERSISTS past the InvalidRefreshTokenException that rejects the
// replay. A wrapping test transaction would keep everything in one never-committed persistence
// context and mask a rollback of the revocations. Because it therefore commits, it's in the
// serial *TransactionalTest suite and relies on truncation (from AbstractTransactionalTest)
// rather than rollback for isolation.
class RefreshTokenServiceTransactionalTest extends AbstractTransactionalTest {

  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  @Test
  void replayTriggeredFamilyRevocationSurvivesTheRejection() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    UUID userId = userService.create(unique + "@example.com", "u" + unique).id();
    UUID organizationId = organizationService.createPersonalOrganization(userId, "u" + unique).id();

    String original = refreshTokenService.mint(userId, organizationId);
    RefreshTokenService.RefreshRotation rotation = refreshTokenService.rotate(original);
    UUID familyId = refreshTokenRepository.findAll().getFirst().getFamilyId();

    // Replay the consumed original: the tripwire rejects it...
    assertThatThrownBy(() -> refreshTokenService.rotate(original))
        .isInstanceOf(InvalidRefreshTokenException.class);

    // ...and the family revocation it wrote must have COMMITTED despite the exception: the live
    // successor is now unusable, in the database, not merely in a rolled-back context.
    assertThatThrownBy(() -> refreshTokenService.rotate(rotation.newRawToken()))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(refreshTokenRepository.findByFamilyId(familyId))
        .isNotEmpty()
        .allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
  }
}
