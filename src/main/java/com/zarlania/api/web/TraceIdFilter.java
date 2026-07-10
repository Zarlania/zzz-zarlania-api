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

  // Groups: 1 = version, 2 = trace-id, 3 = parent-id (trace-flags are not captured).
  private static final Pattern TRACEPARENT =
      Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
  private static final Pattern SIMPLE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  // W3C Trace Context invalid sentinels: version ff is reserved, and an all-zero trace-id or
  // parent-id is defined as invalid — accepting any of these would pin unrelated requests together.
  private static final String INVALID_VERSION = "ff";
  private static final String INVALID_TRACE_ID = "0".repeat(32);
  private static final String INVALID_PARENT_ID = "0".repeat(16);

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
      if (matcher.matches() && isValidTraceparent(matcher)) {
        return matcher.group(2);
      }
    }
    String simple = request.getHeader(TRACE_ID_HEADER);
    if (simple != null && SIMPLE_TRACE_ID.matcher(simple).matches()) {
      return simple;
    }
    return UUID.randomUUID().toString();
  }

  private static boolean isValidTraceparent(Matcher matcher) {
    return !INVALID_VERSION.equals(matcher.group(1))
        && !INVALID_TRACE_ID.equals(matcher.group(2))
        && !INVALID_PARENT_ID.equals(matcher.group(3));
  }
}
