package com.sajtech.notification.application.submit.port.out;

import java.util.function.Supplier;

@FunctionalInterface
public interface TransactionRunner {
  <T> T required(Supplier<T> work);
}
