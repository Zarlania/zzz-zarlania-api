package com.zarlania.api.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the bearer-token security scheme in the public OpenAPI document (ADR-0003) so protected
 * endpoints can reference it and API consumers know how to authenticate. Endpoints outside the
 * security chain's permit-list require a JWT minted by {@code POST /auth/login}.
 */
@Configuration
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class OpenApiSecurityConfig {}
