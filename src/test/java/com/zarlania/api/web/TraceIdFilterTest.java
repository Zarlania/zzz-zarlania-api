package com.zarlania.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// e2e through the real filter chain (@AutoConfigureMockMvc registers servlet filters).
@SpringBootTest
@AutoConfigureMockMvc
class TraceIdFilterTest {

  private static final String VALID_TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Autowired private MockMvc mockMvc;

  @Test
  void usesTraceIdFromTraceparentHeader() throws Exception {
    mockMvc
        .perform(get("/actuator/health").header("traceparent", VALID_TRACEPARENT))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"));
  }

  @Test
  void fallsBackToTraceIdHeader() throws Exception {
    mockMvc
        .perform(get("/actuator/health").header("X-Trace-Id", "my-trace-123"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", "my-trace-123"));
  }

  @Test
  void generatesTraceIdWhenNoHeaderPresent() throws Exception {
    String echoed =
        mockMvc
            .perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Trace-Id");
    assertThat(echoed).isNotBlank();
  }

  @Test
  void ignoresMalformedTraceparentAndGeneratesInstead() throws Exception {
    String echoed =
        mockMvc
            .perform(get("/actuator/health").header("traceparent", "not-a-traceparent"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Trace-Id");
    assertThat(echoed).isNotBlank().isNotEqualTo("not-a-traceparent");
  }

  @Test
  void rejectsUnsafeTraceIdAndGeneratesInstead() throws Exception {
    String unsafe = "abc\r\ninjected";
    String echoed =
        mockMvc
            .perform(get("/actuator/health").header("X-Trace-Id", unsafe))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Trace-Id");
    assertThat(echoed).isNotBlank().isNotEqualTo(unsafe);
  }
}
