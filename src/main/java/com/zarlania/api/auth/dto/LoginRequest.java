package com.zarlania.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials presented to {@code POST /auth/login}. Email format is deliberately not validated
 * here: an unknown email yields the same generic 401 as a wrong password, so rejecting malformed
 * emails differently would add an enumeration signal for no benefit.
 *
 * @param email the account email
 * @param password the account password
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
