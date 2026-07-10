package com.zarlania.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ConstraintViolationsTest {

  @Test
  void matchesWhenAnyCauseNamesTheConstraint() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "wrapper", new RuntimeException("violates UQ_FEATURE_TOGGLES_NAME"));
    assertThat(ConstraintViolations.matches(ex, "uq_feature_toggles_name")).isTrue();
  }

  @Test
  void doesNotMatchAnUnrelatedConstraint() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "wrapper", new RuntimeException("violates uq_something_else"));
    assertThat(ConstraintViolations.matches(ex, "uq_feature_toggles_name")).isFalse();
  }

  @Test
  void isNullSafeOnMessages() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException("wrapper", new RuntimeException((String) null));
    assertThat(ConstraintViolations.matches(ex, "uq_feature_toggles_name")).isFalse();
  }
}
