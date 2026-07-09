package com.zarlania.api.web;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Reads the trace id that {@link TraceIdFilter} stored on the current request. Returns empty when
 * no request is bound to the calling thread (startup, background work).
 */
@Component
public class RequestAttributeCurrentTraceId implements CurrentTraceId {

  @Override
  public Optional<String> get() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return Optional.empty();
    }
    Object value =
        attributes.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    return value instanceof String traceId ? Optional.of(traceId) : Optional.empty();
  }
}
