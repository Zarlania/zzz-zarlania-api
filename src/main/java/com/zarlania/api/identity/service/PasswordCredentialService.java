package com.zarlania.api.identity.service;

import com.zarlania.api.identity.entity.PasswordCredentialEntity;
import com.zarlania.api.identity.exception.PasswordCredentialAlreadyExistsException;
import com.zarlania.api.identity.repository.PasswordCredentialRepository;
import com.zarlania.api.persistence.ConstraintViolations;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates password credentials for the {@code identity} domain. Validates the raw password against
 * {@link PasswordPolicy}, hashes it with the configured {@link PasswordEncoder}, and stores the
 * hash — never the plaintext. The one-credential-per-user invariant is enforced by the {@code
 * uq_password_credentials_user} unique constraint and translated to {@link
 * PasswordCredentialAlreadyExistsException} when hit (mirrors {@code UserService}'s handling of the
 * email/username constraints). Verification is the future seam authentication will add here.
 */
@Service
public class PasswordCredentialService {

  /** Name of the one-per-user unique constraint in {@code V6__create_password_credentials.sql}. */
  private static final String USER_UNIQUE_CONSTRAINT = "uq_password_credentials_user";

  private final PasswordCredentialRepository passwordCredentialRepository;
  private final PasswordPolicy passwordPolicy;
  private final PasswordEncoder passwordEncoder;

  /**
   * A throwaway hash matched against when no credential exists, so verification performs one real
   * Argon2id comparison on every path — response timing must not reveal whether an account or
   * credential exists (user-enumeration defense).
   */
  private final String absentCredentialHash;

  /**
   * Creates the service and precomputes the dummy hash used for constant-work verification (one
   * Argon2id encode at startup).
   *
   * @param passwordCredentialRepository the credential store
   * @param passwordPolicy the account-creation password policy
   * @param passwordEncoder the delegating encoder (Argon2id default)
   */
  public PasswordCredentialService(
      PasswordCredentialRepository passwordCredentialRepository,
      PasswordPolicy passwordPolicy,
      PasswordEncoder passwordEncoder) {
    this.passwordCredentialRepository = passwordCredentialRepository;
    this.passwordPolicy = passwordPolicy;
    this.passwordEncoder = passwordEncoder;
    this.absentCredentialHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /**
   * Validates, hashes, and stores a password credential for the given user.
   *
   * @param userId the owning user's id
   * @param rawPassword the caller-supplied password
   * @throws IllegalArgumentException if the password fails {@link PasswordPolicy}
   * @throws PasswordCredentialAlreadyExistsException if the user already has a credential
   */
  @Transactional
  public void create(UUID userId, String rawPassword) {
    passwordPolicy.validate(rawPassword);
    PasswordCredentialEntity credential = new PasswordCredentialEntity();
    credential.setUserId(userId);
    credential.setPasswordHash(passwordEncoder.encode(rawPassword));
    try {
      // saveAndFlush forces the INSERT now so a duplicate surfaces here as the unique-constraint
      // violation and is reported as the domain exception rather than a raw persistence error.
      passwordCredentialRepository.saveAndFlush(credential);
    } catch (DataIntegrityViolationException ex) {
      if (ConstraintViolations.matches(ex, USER_UNIQUE_CONSTRAINT)) {
        throw PasswordCredentialAlreadyExistsException.forUserId(userId, ex);
      }
      throw ex;
    }
  }

  /**
   * Verifies a raw password against the user's stored credential — the seam ADR-0017 anticipated
   * for authentication.
   *
   * @param userId the claimed user's id; may be null (unknown account), in which case the lookup
   *     runs against a random id and a dummy verification still runs, so the null-userId path costs
   *     the same one indexed lookup plus one Argon2 comparison as a known user — no residual timing
   *     signal distinguishes an unknown email from a known one
   * @param rawPassword the presented password
   * @return whether the password matches the user's stored credential
   */
  @Transactional(readOnly = true)
  public boolean verify(UUID userId, String rawPassword) {
    if (rawPassword == null || rawPassword.isBlank()) {
      return false;
    }
    String storedHash =
        passwordCredentialRepository
            .findByUserId(userId == null ? UUID.randomUUID() : userId)
            .map(PasswordCredentialEntity::getPasswordHash)
            .orElse(null);
    if (userId == null || storedHash == null) {
      passwordEncoder.matches(rawPassword, absentCredentialHash);
      return false;
    }
    return passwordEncoder.matches(rawPassword, storedHash);
  }
}
