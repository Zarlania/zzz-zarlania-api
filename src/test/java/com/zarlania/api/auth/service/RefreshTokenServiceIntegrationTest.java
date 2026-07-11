package com.zarlania.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.auth.entity.RefreshTokenEntity;
import com.zarlania.api.auth.exception.InvalidRefreshTokenException;
import com.zarlania.api.auth.repository.RefreshTokenRepository;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.service.UserService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// Integration tests against the real store: the full rotation lifecycle including the
// stolen-token tripwire (family revocation on replay of a consumed token).
@SpringBootTest
@Transactional
class RefreshTokenServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  private UUID userId;
  private UUID organizationId;

  @BeforeEach
  void createOwningRows() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    userId = userService.create(unique + "@example.com", "u" + unique).id();
    organizationId = organizationService.createPersonalOrganization(userId, "u" + unique).id();
  }

  @Test
  void mintReturnsRawTokenAndStoresOnlyItsHash() {
    String raw = refreshTokenService.mint(userId, organizationId);

    assertThat(raw).isNotBlank();
    assertThat(refreshTokenRepository.findByTokenHash(raw)).isEmpty(); // raw != stored hash
    assertThat(refreshTokenRepository.findAll())
        .anySatisfy(
            row -> {
              assertThat(row.getTokenHash()).hasSize(64).matches("[0-9a-f]{64}");
              assertThat(row.getUserId()).isEqualTo(userId);
              assertThat(row.getOrganizationId()).isEqualTo(organizationId);
              assertThat(row.getConsumedAt()).isNull();
              assertThat(row.getRevokedAt()).isNull();
            });
  }

  @Test
  void rotateConsumesOldTokenAndIssuesNewOneInSameFamily() {
    String raw = refreshTokenService.mint(userId, organizationId);

    RefreshTokenService.RefreshRotation rotation = refreshTokenService.rotate(raw);

    assertThat(rotation.userId()).isEqualTo(userId);
    assertThat(rotation.organizationId()).isEqualTo(organizationId);
    assertThat(rotation.newRawToken()).isNotEqualTo(raw);
    assertThat(refreshTokenRepository.findAll()).hasSize(2);
    assertThat(
            refreshTokenRepository.findAll().stream()
                .map(RefreshTokenEntity::getFamilyId)
                .distinct())
        .hasSize(1);
  }

  @Test
  void replayingConsumedTokenRevokesWholeFamily() {
    String original = refreshTokenService.mint(userId, organizationId);
    RefreshTokenService.RefreshRotation rotation = refreshTokenService.rotate(original);

    // Replay the consumed token: tripwire.
    assertThatThrownBy(() -> refreshTokenService.rotate(original))
        .isInstanceOf(InvalidRefreshTokenException.class);

    // The still-live successor is now revoked too.
    assertThatThrownBy(() -> refreshTokenService.rotate(rotation.newRawToken()))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void rejectsUnknownToken() {
    assertThatThrownBy(() -> refreshTokenService.rotate("never-issued"))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void rejectsExpiredToken() {
    String raw = refreshTokenService.mint(userId, organizationId);
    RefreshTokenEntity row = refreshTokenRepository.findAll().getFirst();
    row.setExpiresAt(Instant.now().minusSeconds(1));
    refreshTokenRepository.saveAndFlush(row);

    assertThatThrownBy(() -> refreshTokenService.rotate(raw))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void revokedTokenCannotRotateAndRevokeIsIdempotent() {
    String raw = refreshTokenService.mint(userId, organizationId);

    refreshTokenService.revoke(raw);
    assertThatCode(() -> refreshTokenService.revoke(raw)).doesNotThrowAnyException();
    assertThatCode(() -> refreshTokenService.revoke("never-issued")).doesNotThrowAnyException();

    assertThatThrownBy(() -> refreshTokenService.rotate(raw))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }
}
