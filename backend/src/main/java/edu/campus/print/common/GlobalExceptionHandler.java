package edu.campus.print.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every failure becomes the same small JSON shape:
 *   {"error": "CODE", "message": "Words a person can act on"}
 * Internal details go to the log with a reference number, never to the screen.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handle(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream().findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Check the values you sent.");
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handle(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", "The request body could not be read."));
    }

    /** A status change that raced with another one (the database guard refused it). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> handle(DataIntegrityViolationException e) {
        log.warn("Refused by the database: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("CONFLICT", "This order changed at the same moment. Refresh and try again."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handle(Exception e, HttpServletRequest req) {
        // Spring's own web errors (404, 405, missing header ...) keep their real status.
        if (e instanceof ErrorResponse er) {
            int status = er.getStatusCode().value();
            return ResponseEntity.status(status).body(body("HTTP_" + status, "Request not accepted (" + status + ")."));
        }
        String ref = Long.toHexString(System.nanoTime());
        log.error("Unhandled error [{}] on {} {}", ref, req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL", "Something went wrong on our side. Reference " + ref));
    }

    private static Map<String, Object> body(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        m.put("at", Instant.now().toString());
        return m;
    }
}
