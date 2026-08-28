package com.sajtech.identity.application.transaction.model;

public enum TransactionFailure {
  TRANSACTION_DEADLINE,
  STATEMENT_TIMEOUT,
  LOCK_TIMEOUT,
  POOL_UNAVAILABLE
}
