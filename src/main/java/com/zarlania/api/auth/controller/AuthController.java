package com.zarlania.api.auth.controller;

import com.zarlania.api.auth.dto.LoginRequest;
import com.zarlania.api.auth.dto.LogoutRequest;
import com.zarlania.api.auth.dto.RefreshRequest;
import com.zarlania.api.auth.dto.TokenResponse;
import com.zarlania.api.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the {@code auth} domain: password login, refresh-token rotation, and logout.
 * Permit-listed in the security chain (these endpoints are how tokens are obtained) and gated by
 * the {@code PASSWORD_LOGIN} toggle (404 while off).
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  /**
   * Logs in with email + password.
   *
   * @param request the validated credentials
   * @return {@code 200 OK} with the org-scoped token pair
   */
  @PostMapping("/login")
  public TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request.email(), request.password());
  }

  /**
   * Rotates a refresh token.
   *
   * @param request the validated rotation request
   * @return {@code 200 OK} with the successor token pair
   */
  @PostMapping("/refresh")
  public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return authService.refresh(request.refreshToken());
  }

  /**
   * Revokes a refresh token (logout). Idempotent.
   *
   * @param request the validated logout request
   * @return {@code 204 No Content}
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.noContent().build();
  }
}
