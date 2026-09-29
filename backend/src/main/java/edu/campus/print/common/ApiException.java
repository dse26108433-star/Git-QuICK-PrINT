package edu.campus.print.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * An error with a code for the client to branch on and a message written for
 * the person reading it, not for the developer who wrote it.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, ?> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    /** details: extra facts for the client, e.g. which document a problem is about. */
    public ApiException(HttpStatus status, String code, String message, Map<String, ?> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, ?> details() { return details; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " was not found.");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
