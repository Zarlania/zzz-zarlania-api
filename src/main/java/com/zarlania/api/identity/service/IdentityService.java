package com.zarlania.api.identity.service;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.service.FeatureToggleService;
import com.zarlania.api.identity.dto.Account;
import com.zarlania.api.logging.LogSanitizer;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates account creation across the {@code users}, {@code organizations}, and (when the
 * {@code PASSWORD_ACCOUNTS} toggle is enabled) {@code identity} credential stores. The public
 * surface of the {@code identity} domain. Injects each collaborator as a Spring bean and exchanges
 * only DTOs (ADR-0011).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdentityService {

  private final UserService userService;
  private final OrganizationService organizationService;
  private final PasswordCredentialService passwordCredentialService;
  private final FeatureToggleService featureToggleService;

  /**
   * Creates an account — a user, their personal organization named after the username, and (when
   * the password-accounts feature is enabled) a password credential — in a single transaction.
   * Because every delegated service joins this transaction, a failure anywhere rolls the whole
   * account back, so no partially-created account remains.
   *
   * <p>The toggle is evaluated globally: there is no organization context at signup, since the
   * personal organization is being created here. When the toggle is off, {@code password} is
   * ignored and the account is created exactly as before.
   *
   * @param email the new user's email
   * @param username the new user's unique public handle
   * @param password the new user's password; honored only when the toggle is enabled, where it is
   *     required and validated by {@code PasswordPolicy}
   * @return the created account (user + personal organization)
   */
  @Transactional
  public Account createAccount(String email, String username, String password) {
    User user = userService.create(email, username);
    Organization personalOrganization =
        organizationService.createPersonalOrganization(user.id(), user.username());
    if (featureToggleService.isEnabled(Feature.PASSWORD_ACCOUNTS)) {
      passwordCredentialService.create(user.id(), password);
    }
    // Log identifiers only — never the email or password (PII/secret). Sanitised via LogSanitizer
    // to keep the CRLF_INJECTION_LOGS detector satisfied.
    log.info(
        "Created account: userId={}, organizationId={}",
        LogSanitizer.forLog(user.id()),
        LogSanitizer.forLog(personalOrganization.id()));
    return new Account(user, personalOrganization);
  }
}
