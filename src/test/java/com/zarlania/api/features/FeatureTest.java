package com.zarlania.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
