package com.vex.server.filter;

/** Raised when a filter expression cannot be lexed or parsed. */
public final class FilterParseException extends RuntimeException {

  /** Creates a new exception with the given message. */
  public FilterParseException(String message) {
    super(message);
  }
}
