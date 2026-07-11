package com.zarlania.api.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class IdentityConfigTest {

  @Autowired private PasswordEncoder passwordEncoder;

  @Test
  void encodesToArgon2PrefixedHashThatIsNotThePlaintext() {
    String raw = "Str0ng!Pass";
    String encoded = passwordEncoder.encode(raw);

    assertThat(encoded).startsWith("{argon2}").doesNotContain(raw);
    assertThat(passwordEncoder.matches(raw, encoded)).isTrue();
    assertThat(passwordEncoder.matches("wrong", encoded)).isFalse();
  }

  @Test
  void encodesWithArgon2idByDefaultAndStillVerifies() {
    PasswordEncoder encoder = new IdentityConfig().passwordEncoder();
    String hash = encoder.encode("Str0ng!Pass");
    assertThat(hash).startsWith("{argon2}");
    assertThat(hash).startsWith("{argon2}$argon2id$"); // id variant, not i/d
    // Guards the production OWASP KDF parameters (19 MiB memory, 2 iterations, parallelism 1)
    // against silent regression: Argon2PasswordEncoder serializes them into the hash itself.
    assertThat(hash).contains("m=19456,t=2,p=1");
    assertThat(encoder.matches("Str0ng!Pass", hash)).isTrue();
    assertThat(encoder.matches("wrong", hash)).isFalse();
  }
}
