package com.zarlania.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A logout request for {@code POST /auth/logout}: revokes the presented refresh token.
 *
 * @param refreshToken the raw refresh token to revoke
 */
public record LogoutRequest(@NotBlank String refreshToken) {}
