package com.sajtech.identity.application.transaction.model;

public final class TransactionUnavailableException extends RuntimeException {
  private final TransactionFailure failure;

  public TransactionUnavailableException(TransactionFailure failure, Throwable cause) {
    super("Identity transaction is unavailable", cause);
    this.failure = failure;
  }

  public TransactionFailure failure() {
    return failure;
  }
}
