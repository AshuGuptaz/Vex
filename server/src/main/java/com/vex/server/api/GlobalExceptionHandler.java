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

@ControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(NoSuchCollectionException.class)
  public ResponseEntity<ErrorResponse> notFound(NoSuchCollectionException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler(FilterParseException.class)
  public ResponseEntity<ErrorResponse> badFilter(FilterParseException e) {
    return ResponseEntity.badRequest()
        .body(new ErrorResponse("filter parse error: " + e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> constraint(ConstraintViolationException e) {
    return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
  }
}
