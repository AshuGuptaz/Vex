package com.vex.server.filter;

/** Raised when a filter expression cannot be lexed or parsed. */
public final class FilterParseException extends RuntimeException {

  public FilterParseException(String message) {
    super(message);
  }
}
