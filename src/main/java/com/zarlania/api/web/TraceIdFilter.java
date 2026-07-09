package com.zarlania.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a trace id: taken from the W3C {@code traceparent} header when present,
 * else from {@code X-Trace-Id}, else freshly generated. The id is stored as a request attribute
 * (see {@link RequestAttributeCurrentTraceId}), put in the logging MDC under {@code traceId}, and
 * echoed on the response as {@code X-Trace-Id} so callers (and chained hops) can propagate it.
 * Inbound values are validated against a strict charset, which also makes them log-safe.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  /** Request-attribute key under which the resolved trace id is stored. */
  public static final String TRACE_ID_ATTRIBUTE = TraceIdFilter.class.getName() + ".TRACE_ID";

  /** Inbound fallback and outbound echo header for the trace id. */
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  private static final String TRACEPARENT_HEADER = "traceparent";
  private static final String MDC_KEY = "traceId";

  private static final Pattern TRACEPARENT =
      Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
  private static final Pattern SIMPLE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = resolveTraceId(request);
    request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
    response.setHeader(TRACE_ID_HEADER, traceId);
    MDC.put(MDC_KEY, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private static String resolveTraceId(HttpServletRequest request) {
    String traceparent = request.getHeader(TRACEPARENT_HEADER);
    if (traceparent != null) {
      Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
      if (matcher.matches()) {
        return matcher.group(1);
      }
    }
    String simple = request.getHeader(TRACE_ID_HEADER);
    if (simple != null && SIMPLE_TRACE_ID.matcher(simple).matches()) {
      return simple;
    }
    return UUID.randomUUID().toString();
  }
}
