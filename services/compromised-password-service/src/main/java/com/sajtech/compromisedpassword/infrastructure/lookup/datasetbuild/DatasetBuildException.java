package com.sajtech.compromisedpassword.infrastructure.lookup.datasetbuild;

public final class DatasetBuildException extends RuntimeException {
  public enum Reason {
    INVALID_SOURCE_LINE,
    EMPTY_SOURCE,
    SOURCE_DIGEST_MISMATCH,
    SQLITE_FAILURE,
    INTEGRITY_FAILURE,
    CONTENT_INVALID,
    COMPATIBILITY_BOUND_EXCEEDED,
    PUBLISH_FAILURE
  }

  private final Reason reason;

  public DatasetBuildException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public DatasetBuildException(Reason reason, long lineNumber) {
    super(reason.name() + " at line " + lineNumber);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
