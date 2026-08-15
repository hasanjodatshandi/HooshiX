package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

public final class DatasetUnavailableException extends RuntimeException {
    public DatasetUnavailableException(String message) {
        super(message);
    }

    public DatasetUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
