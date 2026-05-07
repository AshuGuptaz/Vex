package com.vex.server.filter;

import java.util.Map;

/**
 * A boolean predicate over a payload map. Compiled from a filter expression by {@link
 * FilterCompiler}.
 */
@FunctionalInterface
public interface FilterPredicate {

  boolean test(Map<String, Object> payload);

  /** A predicate that accepts every payload. */
  FilterPredicate ALWAYS_TRUE = p -> true;

  default FilterPredicate and(FilterPredicate other) {
    return p -> this.test(p) && other.test(p);
  }

  default FilterPredicate or(FilterPredicate other) {
    return p -> this.test(p) || other.test(p);
  }

  default FilterPredicate negate() {
    return p -> !this.test(p);
  }
}
