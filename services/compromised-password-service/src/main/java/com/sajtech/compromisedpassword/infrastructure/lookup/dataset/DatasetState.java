package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

public enum DatasetState {
    READY,
    MISSING,
    STALE,
    CORRUPT,
    INCOMPATIBLE,
    UNAVAILABLE
}
