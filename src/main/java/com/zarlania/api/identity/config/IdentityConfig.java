package com.zarlania.api.identity.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Wiring for the {@code identity} domain. Provides the password encoder used to hash credentials at
 * account creation. A {@link DelegatingPasswordEncoder} prefixes each hash with its algorithm id
 * ({@code {argon2}} by default), so the algorithm is self-describing in the stored value and can be
 * migrated later without a schema change. The {@code bcrypt} delegate remains registered so any
 * previously-issued {@code {bcrypt}}-prefixed hash still verifies.
 */
@Configuration
public class IdentityConfig {

  private static final String ENCODER_ID = "argon2";

  // OWASP-recommended Argon2id parameters: 16-byte salt, 32-byte hash,
  // parallelism 1, 19 MiB memory, 2 iterations.
  private static final int SALT_LENGTH_BYTES = 16;
  private static final int HASH_LENGTH_BYTES = 32;
  private static final int PARALLELISM = 1;
  private static final int MEMORY_KIB = 19_456;
  private static final int ITERATIONS = 2;

  private static final int BCRYPT_STRENGTH = 12;

  /**
   * The password encoder for credential hashing: delegating, defaulting to Argon2id with OWASP
   * parameters. The {@code bcrypt} delegate remains registered so any {@code {bcrypt}}-prefixed
   * hash still verifies (an inherent property of the delegating encoder — no rehash machinery
   * exists because no bcrypt hashes exist).
   *
   * @return the delegating password encoder
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    Map<String, PasswordEncoder> encoders =
        Map.of(
            ENCODER_ID,
            new Argon2PasswordEncoder(
                SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS),
            "bcrypt",
            new BCryptPasswordEncoder(BCRYPT_STRENGTH));
    return new DelegatingPasswordEncoder(ENCODER_ID, encoders);
  }
}
