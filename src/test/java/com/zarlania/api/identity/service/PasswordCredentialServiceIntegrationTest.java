package com.zarlania.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.zarlania.api.identity.exception.PasswordCredentialAlreadyExistsException;
import com.zarlania.api.identity.repository.PasswordCredentialRepository;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

// Cross-domain orchestration over a real database, rolled back per method — mirrors
// IdentityServiceIntegrationTest. saveAndFlush executes the INSERT within the test's transaction,
// so the unique-constraint case is still observed before the rollback keeps runs parallel-safe.
@SpringBootTest
@Transactional
class PasswordCredentialServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PasswordCredentialService passwordCredentialService;
  @Autowired private PasswordCredentialRepository passwordCredentialRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private UserService userService;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private User newUser() {
    return userService.create(unique("u") + "@example.com", unique("u"));
  }

  @Test
  void storesTheArgon2HashThatVerifiesAndIsNotThePlaintext() {
    User user = newUser();
    String raw = "Str0ng!Pass";

    passwordCredentialService.create(user.id(), raw);

    var stored = passwordCredentialRepository.findByUserId(user.id()).orElseThrow();
    assertThat(stored.getPasswordHash()).startsWith("{argon2}").isNotEqualTo(raw);
    assertThat(passwordEncoder.matches(raw, stored.getPasswordHash())).isTrue();
  }

  @Test
  void rejectsAnInvalidPasswordWithoutStoringAnything() {
    User user = newUser();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> passwordCredentialService.create(user.id(), "weak"));

    assertThat(passwordCredentialRepository.findByUserId(user.id())).isEmpty();
  }

  @Test
  void rejectsTheSecondCredentialForTheSameUser() {
    User user = newUser();
    passwordCredentialService.create(user.id(), "Str0ng!Pass");

    assertThatExceptionOfType(PasswordCredentialAlreadyExistsException.class)
        .isThrownBy(() -> passwordCredentialService.create(user.id(), "An0ther!Pass"));
  }
}
