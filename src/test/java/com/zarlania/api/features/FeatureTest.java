package com.zarlania.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The code registry contract of the {@link Feature} toggles. */
class FeatureTest {

  @Test
  void everyFeatureHasNonBlankNameAndDescription() {
    for (Feature feature : Feature.values()) {
      assertThat(feature.toggleName()).as("toggleName of %s", feature).isNotBlank();
      assertThat(feature.description()).as("description of %s", feature).isNotBlank();
    }
  }

  @Test
  void passwordAccountsToggleIsRegistered() {
    assertThat(Feature.PASSWORD_ACCOUNTS.toggleName()).isEqualTo("password-accounts");
  }

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
