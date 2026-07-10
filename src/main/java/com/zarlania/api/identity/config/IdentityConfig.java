package com.zarlania.api.identity.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Wiring for the {@code identity} domain. Provides the password encoder used to hash credentials at
 * account creation. A {@link DelegatingPasswordEncoder} prefixes each hash with its algorithm id
 * ({@code {bcrypt}}), so the algorithm is self-describing in the stored value and can be migrated
 * later (e.g. to Argon2) without a schema change — old hashes still verify.
 */
@Configuration
public class IdentityConfig {

  private static final String ENCODER_ID = "bcrypt";
  private static final int BCRYPT_STRENGTH = 12;

  /**
   * The password encoder for credential hashing: delegating, defaulting to bcrypt (strength 12).
   *
   * @return the delegating password encoder
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    Map<String, PasswordEncoder> encoders =
        Map.of(ENCODER_ID, new BCryptPasswordEncoder(BCRYPT_STRENGTH));
    return new DelegatingPasswordEncoder(ENCODER_ID, encoders);
  }
}
