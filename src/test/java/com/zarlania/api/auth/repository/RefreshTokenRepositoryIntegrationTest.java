package com.zarlania.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.auth.entity.RefreshTokenEntity;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.entity.UserEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

// Repository slice test: hash lookup, family listing, and the DB-level invariants
// (unique token_hash, FKs to users/organizations per ADR-0011).
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RefreshTokenRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private TestEntityManager entityManager;

  private UUID persistUser() {
    UserEntity user = new UserEntity();
    String unique = UUID.randomUUID().toString().substring(0, 8);
    user.setEmail(unique + "@example.com");
    user.setUsername("u" + unique);
    return entityManager.persistAndFlush(user).getId();
  }

  private UUID persistOrganization() {
    OrganizationEntity organization = new OrganizationEntity();
    organization.setName("org" + UUID.randomUUID().toString().substring(0, 8));
    organization.setType(OrganizationType.PERSONAL);
    return entityManager.persistAndFlush(organization).getId();
  }

  private RefreshTokenEntity newToken(UUID userId, UUID organizationId, String hash) {
    RefreshTokenEntity token = new RefreshTokenEntity();
    token.setUserId(userId);
    token.setOrganizationId(organizationId);
    token.setTokenHash(hash);
    token.setFamilyId(UUID.randomUUID());
    token.setIssuedAt(Instant.now());
    token.setExpiresAt(Instant.now().plusSeconds(3600));
    return token;
  }

  @Test
  void findsByTokenHash() {
    UUID userId = persistUser();
    UUID organizationId = persistOrganization();
    refreshTokenRepository.saveAndFlush(newToken(userId, organizationId, "a".repeat(64)));

    assertThat(refreshTokenRepository.findByTokenHash("a".repeat(64))).isPresent();
    assertThat(refreshTokenRepository.findByTokenHash("b".repeat(64))).isEmpty();
  }

  @Test
  void listsTokensInFamily() {
    UUID userId = persistUser();
    UUID organizationId = persistOrganization();
    UUID familyId = UUID.randomUUID();
    RefreshTokenEntity first = newToken(userId, organizationId, "c".repeat(64));
    first.setFamilyId(familyId);
    RefreshTokenEntity second = newToken(userId, organizationId, "d".repeat(64));
    second.setFamilyId(familyId);
    refreshTokenRepository.saveAndFlush(first);
    refreshTokenRepository.saveAndFlush(second);

    assertThat(refreshTokenRepository.findByFamilyId(familyId)).hasSize(2);
  }

  @Test
  void consumeIfLiveConsumesExactlyOnceAndSkipsRevokedRows() {
    UUID userId = persistUser();
    UUID organizationId = persistOrganization();
    RefreshTokenEntity live =
        refreshTokenRepository.saveAndFlush(newToken(userId, organizationId, "g".repeat(64)));
    RefreshTokenEntity revoked = newToken(userId, organizationId, "h".repeat(64));
    revoked.setRevokedAt(Instant.now());
    revoked = refreshTokenRepository.saveAndFlush(revoked);

    assertThat(refreshTokenRepository.consumeIfLive(live.getId(), Instant.now())).isEqualTo(1);
    assertThat(refreshTokenRepository.consumeIfLive(live.getId(), Instant.now())).isZero();
    assertThat(refreshTokenRepository.consumeIfLive(revoked.getId(), Instant.now())).isZero();
  }

  @Test
  void rejectsDuplicateTokenHash() {
    UUID userId = persistUser();
    UUID organizationId = persistOrganization();
    refreshTokenRepository.saveAndFlush(newToken(userId, organizationId, "e".repeat(64)));

    assertThatThrownBy(
            () ->
                refreshTokenRepository.saveAndFlush(
                    newToken(userId, organizationId, "e".repeat(64))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_refresh_tokens_hash");
  }

  @Test
  void rejectsUnknownUserAndOrganization() {
    assertThatThrownBy(
            () ->
                refreshTokenRepository.saveAndFlush(
                    newToken(UUID.randomUUID(), UUID.randomUUID(), "f".repeat(64))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
