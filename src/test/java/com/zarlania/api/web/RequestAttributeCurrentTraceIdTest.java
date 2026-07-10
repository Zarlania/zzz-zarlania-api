package com.zarlania.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RequestAttributeCurrentTraceIdTest {

  private final RequestAttributeCurrentTraceId currentTraceId =
      new RequestAttributeCurrentTraceId();

  @AfterEach
  void resetRequestAttributes() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void getReturnsTraceIdWhenRequestIsBound() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-123");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    Optional<String> result = currentTraceId.get();

    assertThat(result).contains("trace-123");
  }

  @Test
  void getReturnsEmptyWhenNoRequestBound() {
    RequestContextHolder.resetRequestAttributes();

    Optional<String> result = currentTraceId.get();

    assertThat(result).isEmpty();
  }

  @Test
  void getReturnsEmptyWhenAttributeMissing() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    Optional<String> result = currentTraceId.get();

    assertThat(result).isEmpty();
  }
}
