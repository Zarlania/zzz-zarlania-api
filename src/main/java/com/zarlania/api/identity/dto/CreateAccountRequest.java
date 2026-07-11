package com.zarlania.api.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code POST /accounts}, validated at the HTTP boundary. The email/username
 * size limits mirror the {@code users.email} (320) and {@code users.username} (100) columns; these
 * bounds also exist as domain invariants in {@code UserService} (defense in depth at two boundaries
 * — the only accepted duplication, since exposing the {@code users} constants here would breach the
 * domain boundary).
 *
 * <p>{@code password} is optional at the type level and carries no bean-validation annotations: it
 * is only consulted when the {@code PASSWORD_ACCOUNTS} feature toggle is enabled, and is then
 * validated at runtime by {@code PasswordPolicy} (required-ness depends on toggle state, which a
 * static annotation cannot express). When the toggle is off it is ignored entirely. Unknown extra
 * fields in the body are silently ignored (Spring's default {@code FAIL_ON_UNKNOWN_PROPERTIES=
 * false}) so the frontend may send fields the current toggle state does not act on.
 *
 * @param email the new user's email
 * @param username the new user's unique public handle
 * @param password the new user's password, honored only when the password-accounts toggle is on
 */
public record CreateAccountRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(max = 100) String username,
    String password) {}
