package com.zarlania.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A refresh-token rotation request for {@code POST /auth/refresh}.
 *
 * @param refreshToken the raw refresh token issued at login or by a previous refresh
 */
public record RefreshRequest(@NotBlank String refreshToken) {}
