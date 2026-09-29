package com.github.guoyyit.corporateactionsjava.core.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    DUPLICATE_EVENT(HttpStatus.CONFLICT),
    CONFLICTING_EVENT(HttpStatus.CONFLICT),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    VERSION_CONFLICT(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
