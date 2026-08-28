package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.transaction.model.TransactionFailure;
import com.sajtech.identity.application.transaction.model.TransactionProfile;
import com.sajtech.identity.application.transaction.model.TransactionUnavailableException;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

public final class SpringTransactionRunner implements TransactionRunner {
  private static final Map<TransactionProfile, TransactionBudget> BUDGETS = budgets();
  private final Map<TransactionProfile, TransactionTemplate> templates;
  private final DSLContext dsl;

  public SpringTransactionRunner(PlatformTransactionManager transactionManager, DSLContext dsl) {
    this.dsl = dsl;
    EnumMap<TransactionProfile, TransactionTemplate> configured =
        new EnumMap<>(TransactionProfile.class);
    BUDGETS.forEach(
        (profile, budget) -> {
          TransactionTemplate template = new TransactionTemplate(transactionManager);
          template.setName("identity-" + profile.name().toLowerCase(java.util.Locale.ROOT));
          template.setTimeout(budget.transactionTimeoutSeconds());
          configured.put(profile, template);
        });
    this.templates = Map.copyOf(configured);
  }

  @Override
  public <T> T required(Supplier<T> work) {
    return required(TransactionProfile.INTERACTIVE, work);
  }

  @Override
  public <T> T required(TransactionProfile profile, Supplier<T> work) {
    if (profile == null || work == null) {
      throw new IllegalArgumentException("Transaction profile and work are required");
    }
    TransactionBudget budget = BUDGETS.get(profile);
    try {
      return templates
          .get(profile)
          .execute(
              status -> {
                dsl.fetchValue(
                    "SELECT set_config('lock_timeout', ?, true)",
                    budget.lockTimeoutMillis() + "ms");
                dsl.fetchValue(
                    "SELECT set_config('statement_timeout', ?, true)",
                    budget.statementTimeoutMillis() + "ms");
                return work.get();
              });
    } catch (RuntimeException exception) {
      TransactionFailure failure = classify(exception);
      if (failure == null) throw exception;
      throw new TransactionUnavailableException(failure, exception);
    }
  }

  private static Map<TransactionProfile, TransactionBudget> budgets() {
    EnumMap<TransactionProfile, TransactionBudget> result = new EnumMap<>(TransactionProfile.class);
    result.put(TransactionProfile.INTERACTIVE, new TransactionBudget(1, 500, 100));
    result.put(TransactionProfile.WORK_CLAIM, new TransactionBudget(1, 500, 100));
    result.put(TransactionProfile.MAINTENANCE, new TransactionBudget(3, 2000, 100));
    return Map.copyOf(result);
  }

  private static TransactionFailure classify(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
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
          || current instanceof CannotGetJdbcConnectionException) {
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

  private record TransactionBudget(
      int transactionTimeoutSeconds, int statementTimeoutMillis, int lockTimeoutMillis) {}
}
