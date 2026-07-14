package com.zarlania.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A refresh-token rotation request for {@code POST /auth/refresh}. The {@code @Size} cap bounds
 * attacker-controlled work (a token-hash lookup) at the boundary; 512 sits far above a raw token's
 * actual length (43 chars) so no legitimate token is ever rejected.
 *
 * @param refreshToken the raw refresh token issued at login or by a previous refresh (max 512
 *     chars)
 */
public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {}
