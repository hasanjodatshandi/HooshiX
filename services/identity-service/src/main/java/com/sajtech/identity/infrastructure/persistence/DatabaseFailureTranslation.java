package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.transaction.model.TransactionFailure;
import com.sajtech.identity.application.transaction.model.TransactionUnavailableException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.jooq.ExecuteContext;
import org.springframework.boot.jooq.autoconfigure.ExceptionTranslatorExecuteListener;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/** Applies the same bounded failure contract to transactional and direct jOOQ operations. */
public final class DatabaseFailureTranslation implements ExceptionTranslatorExecuteListener {
  @Override
  public void exception(ExecuteContext context) {
    RuntimeException exception = context.exception();
    TransactionFailure failure = classify(exception);
    if (failure != null) {
      context.exception(new TransactionUnavailableException(failure, exception));
    } else {
      ExceptionTranslatorExecuteListener.DEFAULT.exception(context);
    }
  }

  static TransactionFailure classify(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof TransactionUnavailableException unavailable) {
        return unavailable.failure();
      }
      if (current instanceof TransactionTimedOutException) {
        return TransactionFailure.TRANSACTION_DEADLINE;
      }
      if (current instanceof CannotAcquireLockException) {
        return TransactionFailure.LOCK_TIMEOUT;
      }
      if (current instanceof QueryTimeoutException) {
        return TransactionFailure.STATEMENT_TIMEOUT;
      }
      if (current instanceof CannotCreateTransactionException
          || current instanceof CannotGetJdbcConnectionException
          || current instanceof SQLTransientConnectionException) {
        return TransactionFailure.POOL_UNAVAILABLE;
      }
      if (current instanceof SQLException sql) {
        if ("55P03".equals(sql.getSQLState())) return TransactionFailure.LOCK_TIMEOUT;
        if ("57014".equals(sql.getSQLState())) return TransactionFailure.STATEMENT_TIMEOUT;
        if (sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
          return TransactionFailure.POOL_UNAVAILABLE;
        }
      }
    }
    return null;
  }
}
