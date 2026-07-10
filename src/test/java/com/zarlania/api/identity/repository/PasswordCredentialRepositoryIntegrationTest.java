package com.zarlania.api.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.identity.entity.PasswordCredentialEntity;
import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserMapper;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, UserService.class, UserMapper.class})
class PasswordCredentialRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PasswordCredentialRepository passwordCredentialRepository;
  @Autowired private UserService userService;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private User newUser() {
    return userService.create(unique("u") + "@example.com", unique("u"));
  }

  private PasswordCredentialEntity credentialFor(UUID userId) {
    PasswordCredentialEntity credential = new PasswordCredentialEntity();
    credential.setUserId(userId);
    credential.setPasswordHash("{bcrypt}$2a$12$" + UUID.randomUUID().toString().replace("-", ""));
    return credential;
  }

  @Test
  void persistsAndFindsByUserId() {
    User user = newUser();
    passwordCredentialRepository.saveAndFlush(credentialFor(user.id()));

    assertThat(passwordCredentialRepository.findByUserId(user.id()))
        .get()
        .extracting(PasswordCredentialEntity::getUserId)
        .isEqualTo(user.id());
  }

  @Test
  void rejectsSecondCredentialForSameUser() {
    User user = newUser();
    passwordCredentialRepository.saveAndFlush(credentialFor(user.id()));

    assertThatThrownBy(() -> passwordCredentialRepository.saveAndFlush(credentialFor(user.id())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
