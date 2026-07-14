package com.zarlania.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.features.service.FeatureToggleAdminService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// Integration test through the real encoder/decoder beans: the claims contract is the
// hard-to-change part of the auth design (see the org-scoped JWT ADR), so it is asserted
// literally here. Also proves a minted token passes chain enforcement end to end.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JwtIssuerTest {

  @Autowired private JwtIssuer jwtIssuer;
  @Autowired private JwtEncoder jwtEncoder;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private MockMvc mockMvc;
  @Autowired private FeatureToggleAdminService featureToggleAdminService;

  @Test
  void mintsOrgScopedUserTokenWithContractClaims() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    Jwt jwt = jwtDecoder.decode(jwtIssuer.issueUserToken(userId, organizationId));

    assertThat(jwt.getSubject()).isEqualTo(userId.toString());
    assertThat(jwt.getClaimAsString("org")).isEqualTo(organizationId.toString());
    assertThat(jwt.getClaimAsString("token_use")).isEqualTo("user");
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("zarlania-api");
    assertThat(jwt.getId()).isNotBlank();
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
        .isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void everyTokenGetsFreshJti() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    Jwt first = jwtDecoder.decode(jwtIssuer.issueUserToken(userId, organizationId));
    Jwt second = jwtDecoder.decode(jwtIssuer.issueUserToken(userId, organizationId));
    assertThat(first.getId()).isNotEqualTo(second.getId());
  }

  @Test
  void exposesTheConfiguredTtlInSeconds() {
    assertThat(jwtIssuer.accessTokenTtlSeconds()).isEqualTo(900L);
  }

  @Test
  void mintedTokenPassesChainEnforcement() throws Exception {
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
    String token = jwtIssuer.issueUserToken(UUID.randomUUID(), UUID.randomUUID());
    mockMvc
        .perform(get("/api/admin/feature-toggles").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  void tamperedTokenFailsChainEnforcement() throws Exception {
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
    String token = jwtIssuer.issueUserToken(UUID.randomUUID(), UUID.randomUUID());
    String tampered = token.substring(0, token.length() - 4) + "AAAA";
    mockMvc
        .perform(get("/api/admin/feature-toggles").header("Authorization", "Bearer " + tampered))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenFailsChainEnforcement() throws Exception {
    // Crafted directly with the encoder: exp two hours in the past, comfortably beyond
    // the decoder's default 60-second clock skew.
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
    Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("zarlania-api")
            .subject(UUID.randomUUID().toString())
            .claim("org", UUID.randomUUID().toString())
            .claim("token_use", "user")
            .id(UUID.randomUUID().toString())
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
            .build();
    String expired =
        jwtEncoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    mockMvc
        .perform(get("/api/admin/feature-toggles").header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }
}
