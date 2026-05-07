package com.vex.server.api;

import com.vex.server.api.dto.Dtos.ErrorResponse;
import com.vex.server.domain.CollectionManager.NoSuchCollectionException;
import com.vex.server.filter.FilterParseException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** Maps domain exceptions to HTTP status codes + JSON error bodies. */
@ControllerAdvice
public class GlobalExceptionHandler {

  /** {@link NoSuchCollectionException} -> 404. */
  @ExceptionHandler(NoSuchCollectionException.class)
  public ResponseEntity<ErrorResponse> notFound(NoSuchCollectionException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
  }

  /** Domain validation failures (e.g. duplicate id, dim mismatch) -> 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
  }

  /** Filter parse / lex failures -> 400 with a tagged message. */
  @ExceptionHandler(FilterParseException.class)
  public ResponseEntity<ErrorResponse> badFilter(FilterParseException e) {
    return ResponseEntity.badRequest()
        .body(new ErrorResponse("filter parse error: " + e.getMessage()));
  }

  /** Spring's bean-validation failures on @Valid request bodies -> 400. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
  }

  /** Path-variable / parameter constraint violations -> 400. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> constraint(ConstraintViolationException e) {
    return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
  }
}
