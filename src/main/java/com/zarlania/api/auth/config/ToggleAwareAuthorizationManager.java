package com.zarlania.api.auth.config;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.service.FeatureToggleService;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Authorization rule for every path outside the public permit-list: while the {@code
 * AUTH_ENFORCEMENT} toggle is off the request is permitted (pre-auth behavior, per ADR-0016's
 * gate-everything rule); once on, a valid authenticated JWT is required. When the toggle goes
 * permanent this class is deleted and the chain uses {@code .authenticated()} directly.
 */
@Component
@RequiredArgsConstructor
public class ToggleAwareAuthorizationManager
    implements AuthorizationManager<RequestAuthorizationContext> {

  private static final AuthorizationManager<RequestAuthorizationContext> REQUIRE_AUTHENTICATED =
      AuthenticatedAuthorizationManager.authenticated();

  private final FeatureToggleService featureToggleService;

  @Override
  public AuthorizationResult authorize(
      Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
    if (!featureToggleService.isEnabled(Feature.AUTH_ENFORCEMENT)) {
      return new AuthorizationDecision(true);
    }
    return REQUIRE_AUTHENTICATED.authorize(authentication, context);
  }
}
