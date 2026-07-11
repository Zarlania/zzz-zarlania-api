package com.zarlania.api.identity.controller;

import com.zarlania.api.identity.dto.Account;
import com.zarlania.api.identity.dto.CreateAccountRequest;
import com.zarlania.api.identity.service.IdentityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for the {@code identity} domain. */
@RestController
@RequiredArgsConstructor
public class IdentityController {

  private final IdentityService identityService;

  /**
   * Creates an account: a user, their personal organization, and (when the password-accounts
   * feature is enabled) a password credential.
   *
   * @param request the validated account-creation payload
   * @return {@code 201 Created} with the created {@link Account}
   */
  @PostMapping("/accounts")
  public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountRequest request) {
    Account account =
        identityService.createAccount(request.email(), request.username(), request.password());
    return ResponseEntity.status(HttpStatus.CREATED).body(account);
  }
}
