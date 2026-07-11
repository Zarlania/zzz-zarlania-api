package com.zarlania.api.identity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  void accepts128CharacterPassword() {
    // 124 filler chars + the 4 required classes = exactly 128.
    String password = "Aa1!" + "x".repeat(124);
    assertThatCode(() -> policy.validate(password)).doesNotThrowAnyException();
  }

  @Test
  void rejects129CharacterPassword() {
    String password = "Aa1!" + "x".repeat(125);
    assertThatThrownBy(() -> policy.validate(password))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("password must be at most 128 characters");
  }

  @Test
  void countsCodePointsNotUtf16UnitsForMinimumLength() {
    // "Aa1!" + two U+1F600 emoji = 8 UTF-16 code units but only 6 code points, so it is too short
    // and must be rejected — a char-count check would have wrongly accepted it.
    String sixCodePoints = "Aa1!😀😀";
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(sixCodePoints));
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
