package com.zarlania.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials presented to {@code POST /auth/login}. Email format is deliberately not validated
 * here: an unknown email yields the same generic 401 as a wrong password, so rejecting malformed
 * emails differently would add an enumeration signal for no benefit. The {@code @Size} caps bound
 * attacker-controlled work (a credential lookup / an Argon2 comparison) at the boundary; both caps
 * sit far above any legitimate value — the {@code users.email} column is 320 chars, and {@link
 * com.zarlania.api.identity.service.PasswordPolicy} already caps passwords at 128 — so no
 * legitimate credential is ever rejected.
 *
 * @param email the account email (max 320 chars, matching the {@code users.email} column bound)
 * @param password the account password (max 512 chars)
 */
public record LoginRequest(
    @NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 512) String password) {}
