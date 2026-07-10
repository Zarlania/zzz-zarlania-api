package com.zarlania.api.identity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy();

  @Test
  void acceptsStrongPassword() {
    assertThatCode(() -> policy.validate("Str0ng!Pass")).doesNotThrowAnyException();
  }

  @Test
  void rejectsNull() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(null));
  }

  @Test
  void rejectsBlank() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("   "));
  }

  @Test
  void rejectsTooShort() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("Ab1!x"));
  }

  @Test
  void rejectsOver72Bytes() {
    String longPassword = "Aa1!" + "a".repeat(70); // 74 bytes
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(longPassword));
  }

  @Test
  void rejectsMissingUppercase() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("str0ng!pass"));
  }

  @Test
  void rejectsMissingLowercase() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("STR0NG!PASS"));
  }

  @Test
  void rejectsMissingDigit() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("Strong!Pass"));
  }

  @Test
  void rejectsMissingSymbol() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("Str0ngPass"));
  }

  @Test
  void rejectsWhenNoncasedCharacterIsNotSymbol() {
    // 中 is a letter (not upper/lower/digit and not a symbol), so this password has no real symbol.
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("Aaaaaa1中"));
  }

  @Test
  void errorMessageNeverContainsThePassword() {
    String secret = "sneaky";
    assertThatIllegalArgumentException()
        .isThrownBy(() -> policy.validate(secret))
        .withMessageNotContaining(secret);
  }
}
