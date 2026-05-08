package com.vex.server.filter;

import java.util.Map;

/**
 * A boolean predicate over a payload map. Compiled from a filter expression by {@link
 * FilterCompiler}.
 */
@FunctionalInterface
public interface FilterPredicate {

  /** Returns true if the payload satisfies this predicate. */
  boolean test(Map<String, Object> payload);

  /** A predicate that accepts every payload. */
  FilterPredicate ALWAYS_TRUE = p -> true;

  /** Returns a predicate that's true iff both this and {@code other} are true. Short-circuits. */
  default FilterPredicate and(FilterPredicate other) {
    return p -> this.test(p) && other.test(p);
  }

  /** Returns a predicate that's true iff this or {@code other} is true. Short-circuits. */
  default FilterPredicate or(FilterPredicate other) {
    return p -> this.test(p) || other.test(p);
  }

  /** Returns the logical negation of this predicate. */
  default FilterPredicate negate() {
    return p -> !this.test(p);
  }
}
