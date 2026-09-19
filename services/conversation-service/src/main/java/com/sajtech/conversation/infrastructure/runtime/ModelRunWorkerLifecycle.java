package com.sajtech.conversation.infrastructure.runtime;

import com.sajtech.conversation.application.service.ModelRunWorker;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

public final class ModelRunWorkerLifecycle implements SmartLifecycle {
  private final ModelRunWorker worker;
  private final Duration pollInterval;
  private final int concurrency;
  private volatile boolean running;
  private ScheduledExecutorService executor;

  public ModelRunWorkerLifecycle(ModelRunWorker worker, Duration pollInterval, int concurrency) {
    this.worker = Objects.requireNonNull(worker);
    this.pollInterval = Objects.requireNonNull(pollInterval);
    if (pollInterval.isZero() || pollInterval.isNegative() || concurrency < 1) {
      throw new IllegalArgumentException("Model worker lifecycle configuration is invalid");
    }
    this.concurrency = concurrency;
  }

  @Override
  public synchronized void start() {
    if (running) return;
    executor =
        Executors.newScheduledThreadPool(
            concurrency, Thread.ofPlatform().name("model-run-worker-", 0).factory());
    for (int index = 0; index < concurrency; index++) {
      executor.scheduleWithFixedDelay(
          this::runSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }
    running = true;
  }

  private void runSafely() {
    try {
      worker.runOnce();
    } catch (RuntimeException ignored) {
      // The durable lease retains uncertain work. No exception or content is logged here.
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (executor != null) executor.shutdownNow();
    executor = null;
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
