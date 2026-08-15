package com.sajtech.compromisedpassword.infrastructure.lookup.runtime;

public final class LookupCapacityExceededException extends RuntimeException {
    public LookupCapacityExceededException() {
        super("Lookup capacity is exhausted");
    }
}
