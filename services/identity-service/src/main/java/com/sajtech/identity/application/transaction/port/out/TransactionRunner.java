package com.sajtech.identity.application.transaction.port.out;

import com.sajtech.identity.application.transaction.model.TransactionProfile;
import java.util.function.Supplier;

public interface TransactionRunner {
  <T> T required(Supplier<T> work);

  default <T> T required(TransactionProfile profile, Supplier<T> work) {
    return required(work);
  }
}
