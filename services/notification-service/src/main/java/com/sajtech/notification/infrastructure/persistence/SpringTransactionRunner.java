package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.submit.port.out.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class SpringTransactionRunner implements TransactionRunner {
  private final TransactionTemplate template;

  public SpringTransactionRunner(PlatformTransactionManager transactionManager) {
    this.template = new TransactionTemplate(transactionManager);
  }

  @Override
  public <T> T required(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
