package com.zarlania.api.web;

import java.util.Optional;

/**
 * Supplies the current request's trace id, if the caller is executing within a traced request. An
 * interface so domain services can be unit-tested with a fixed trace id.
 */
public interface CurrentTraceId {

  /**
   * Returns the current request's trace id.
   *
   * @return the trace id, or empty when not executing within an HTTP request
   */
  Optional<String> get();
}
