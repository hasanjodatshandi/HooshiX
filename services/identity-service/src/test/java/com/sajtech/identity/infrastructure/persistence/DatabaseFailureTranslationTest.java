package com.sajtech.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sajtech.identity.application.transaction.model.TransactionFailure;
import com.sajtech.identity.application.transaction.model.TransactionUnavailableException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;

class DatabaseFailureTranslationTest {
  @Test
  void translatesNullSqlStateConnectionAcquisitionFailureWithoutInspectingSensitiveText() {
    RuntimeException original =
        new TransientDataAccessResourceException(
            "private SQL detail", new SQLTransientConnectionException("private pool detail"));
    ExecuteContext context = mock(ExecuteContext.class);
    when(context.exception()).thenReturn(original);

    new DatabaseFailureTranslation().exception(context);

    ArgumentCaptor<RuntimeException> captured = ArgumentCaptor.forClass(RuntimeException.class);
    verify(context).exception(captured.capture());
    assertThat(captured.getValue())
        .isInstanceOfSatisfying(
            TransactionUnavailableException.class,
            failure -> assertThat(failure.failure()).isEqualTo(TransactionFailure.POOL_UNAVAILABLE))
        .hasMessage("Identity transaction is unavailable")
        .hasCause(original);
    verify(context, never()).sql();
  }

  @ParameterizedTest
  @CsvSource({"55P03,LOCK_TIMEOUT", "57014,STATEMENT_TIMEOUT", "08006,POOL_UNAVAILABLE"})
  void preservesReviewedSqlStateCategories(String sqlState, TransactionFailure expected) {
    assertThat(DatabaseFailureTranslation.classify(new SQLException("private", sqlState)))
        .isEqualTo(expected);
  }

  @Test
  void unknownFailuresAreNotMisclassifiedAndConstraintTranslationIsPreserved() {
    SQLException constraint = new SQLException("private constraint detail", "23505");
    assertThat(DatabaseFailureTranslation.classify(constraint)).isNull();
    assertThat(DatabaseFailureTranslation.classify(new IllegalStateException("private"))).isNull();
    ExecuteContext context = mock(ExecuteContext.class);
    when(context.exception())
        .thenReturn(new org.jooq.exception.DataAccessException("private", constraint));
    when(context.sqlException()).thenReturn(constraint);
    when(context.configuration()).thenReturn(new DefaultConfiguration().set(SQLDialect.POSTGRES));

    new DatabaseFailureTranslation().exception(context);

    ArgumentCaptor<RuntimeException> captured = ArgumentCaptor.forClass(RuntimeException.class);
    verify(context).exception(captured.capture());
    assertThat(captured.getValue()).isInstanceOf(DuplicateKeyException.class);
  }
}
