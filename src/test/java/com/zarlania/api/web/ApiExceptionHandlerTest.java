package com.zarlania.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void mapsIllegalArgumentToBadRequest() {
    ProblemDetail problem =
        handler.handleIllegalArgument(new IllegalArgumentException("email must not be blank"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("email must not be blank");
  }

  @Test
  void mapsPasswordCredentialConflictToConflict() {
    ProblemDetail problem =
        handler.handlePasswordCredentialConflict(
            com.zarlania.api.identity.exception.PasswordCredentialAlreadyExistsException.forUserId(
                java.util.UUID.randomUUID(), null));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).isEqualTo("A password credential already exists for this user");
  }
}
