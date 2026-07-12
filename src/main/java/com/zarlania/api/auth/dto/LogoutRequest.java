package com.zarlania.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A logout request for {@code POST /auth/logout}: revokes the presented refresh token. The
 * {@code @Size} cap bounds attacker-controlled work (a token-hash lookup) at the boundary; 512 sits
 * far above a raw token's actual length (43 chars) so no legitimate token is ever rejected.
 *
 * @param refreshToken the raw refresh token to revoke (max 512 chars)
 */
public record LogoutRequest(@NotBlank @Size(max = 512) String refreshToken) {}
