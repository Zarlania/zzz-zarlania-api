package com.zarlania.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The code registry contract for the auth toggles introduced by issue #75. */
class FeatureTest {

  @Test
  void passwordLoginToggleIsRegistered() {
    assertThat(Feature.PASSWORD_LOGIN.toggleName()).isEqualTo("password-login");
    assertThat(Feature.PASSWORD_LOGIN.description()).isNotBlank();
  }

  @Test
  void authEnforcementToggleIsRegistered() {
    assertThat(Feature.AUTH_ENFORCEMENT.toggleName()).isEqualTo("auth-enforcement");
    assertThat(Feature.AUTH_ENFORCEMENT.description()).isNotBlank();
  }

  @Test
  void toggleNamesAreUnique() {
    long distinct = Arrays.stream(Feature.values()).map(Feature::toggleName).distinct().count();
    assertThat(distinct).isEqualTo(Feature.values().length);
  }
}
