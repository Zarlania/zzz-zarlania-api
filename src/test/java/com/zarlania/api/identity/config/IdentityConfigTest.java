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
  void encodesToBcryptPrefixedHashThatIsNotThePlaintext() {
    String raw = "Str0ng!Pass";
    String encoded = passwordEncoder.encode(raw);

    assertThat(encoded).startsWith("{bcrypt}").doesNotContain(raw);
    assertThat(passwordEncoder.matches(raw, encoded)).isTrue();
    assertThat(passwordEncoder.matches("wrong", encoded)).isFalse();
  }
}
