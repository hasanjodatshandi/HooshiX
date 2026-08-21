package com.sajtech.identity.application.transaction.port.out;

import java.util.function.Supplier;

public interface TransactionRunner {
  <T> T required(Supplier<T> work);
}
