package com.zarlania.api.auth.service;

import com.zarlania.api.auth.dto.TokenResponse;
import com.zarlania.api.auth.exception.InvalidCredentialsException;
import com.zarlania.api.auth.exception.InvalidRefreshTokenException;
import com.zarlania.api.auth.exception.PasswordLoginDisabledException;
import com.zarlania.api.auth.service.RefreshTokenService.RefreshRotation;
import com.zarlania.api.features.Feature;
import com.zarlania.api.features.service.FeatureToggleService;
import com.zarlania.api.identity.service.PasswordCredentialService;
import com.zarlania.api.logging.LogSanitizer;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the token lifecycle: login (verify the identity-owned credential, then mint an
 * access/refresh pair scoped to the user's personal organization), refresh (rotate), and logout
 * (revoke). The public surface of the {@code auth} domain; exchanges only DTOs with the {@code
 * users}, {@code identity}, and {@code organizations} domains (ADR-0011). The whole surface is
 * gated by the {@code PASSWORD_LOGIN} toggle (404 off), evaluated globally — no organization
 * context exists before a token is issued.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

  private final FeatureToggleService featureToggleService;
  private final UserService userService;
  private final PasswordCredentialService passwordCredentialService;
  private final OrganizationService organizationService;
  private final JwtIssuer jwtIssuer;
  private final RefreshTokenService refreshTokenService;

  /**
   * Verifies the email + password and mints a token pair scoped to the user's personal
   * organization. Every failure — unknown email, missing credential, wrong password, or a user with
   * no personal organization — is the same {@link InvalidCredentialsException}, and verification
   * does constant work on all paths (see {@code PasswordCredentialService#verify}), so neither
   * response nor timing reveals whether an account exists.
   *
   * @param email the account email
   * @param password the account password
   * @return the minted token pair
   * @throws PasswordLoginDisabledException if the {@code PASSWORD_LOGIN} toggle is off
   * @throws InvalidCredentialsException if the credentials do not verify
   */
  @Transactional
  public TokenResponse login(String email, String password) {
    requirePasswordLoginEnabled();
    UUID userId = userService.findByEmail(email).map(User::id).orElse(null);
    if (!passwordCredentialService.verify(userId, password)) {
      throw new InvalidCredentialsException();
    }
    Organization personalOrganization =
        organizationService
            .findPersonalOrganization(userId)
            .orElseThrow(InvalidCredentialsException::new);
    String accessToken = jwtIssuer.issueUserToken(userId, personalOrganization.id());
    String refreshToken = refreshTokenService.mint(userId, personalOrganization.id());
    log.info(
        "Login: userId={}, organizationId={}",
        LogSanitizer.forLog(userId),
        LogSanitizer.forLog(personalOrganization.id()));
    return new TokenResponse(accessToken, jwtIssuer.accessTokenTtlSeconds(), refreshToken);
  }

  /**
   * Rotates a refresh token and mints a fresh access token for the same user and organization.
   *
   * <p>Carries {@code noRollbackFor = InvalidRefreshTokenException.class}, mirroring {@link
   * RefreshTokenService#rotate}: that method revokes a stolen token's whole family and then throws
   * {@link InvalidRefreshTokenException}, relying on its own {@code noRollbackFor} so the
   * revocations commit. But the exception still has to cross this method's transactional boundary
   * to reach the controller, and a boundary with the default rollback rules would re-mark the
   * (shared, REQUIRED-propagated) transaction rollback-only as the exception passes through it —
   * silently undoing the revocations {@code rotate} just committed. This method must carry the same
   * {@code noRollbackFor} so the outer boundary agrees with the inner one.
   *
   * @param refreshToken the presented raw refresh token
   * @return the successor token pair
   * @throws PasswordLoginDisabledException if the {@code PASSWORD_LOGIN} toggle is off
   * @throws InvalidRefreshTokenException if the token is unknown, expired, revoked, or replayed
   *     (replay also revokes its family)
   */
  @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
  public TokenResponse refresh(String refreshToken) {
    requirePasswordLoginEnabled();
    RefreshRotation rotation = refreshTokenService.rotate(refreshToken);
    String accessToken = jwtIssuer.issueUserToken(rotation.userId(), rotation.organizationId());
    return new TokenResponse(
        accessToken, jwtIssuer.accessTokenTtlSeconds(), rotation.newRawToken());
  }

  /**
   * Revokes the presented refresh token. Idempotent — repeated or unknown tokens still succeed, so
   * logout never fails. The paired access token stays valid up to its remaining TTL (≤ 15 minutes):
   * the deliberate trade-off of stateless access tokens.
   *
   * @param refreshToken the raw refresh token to revoke
   * @throws PasswordLoginDisabledException if the {@code PASSWORD_LOGIN} toggle is off
   */
  @Transactional
  public void logout(String refreshToken) {
    requirePasswordLoginEnabled();
    refreshTokenService.revoke(refreshToken);
  }

  private void requirePasswordLoginEnabled() {
    if (!featureToggleService.isEnabled(Feature.PASSWORD_LOGIN)) {
      throw new PasswordLoginDisabledException();
    }
  }
}
