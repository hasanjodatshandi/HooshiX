package com.sajtech.webbff.configuration;

import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;

public final class SecurityMaterialRefresher implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(SecurityMaterialRefresher.class);
  private final List<FileBackedKeyRing> rings;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("web-bff-security-material-refresh").factory());
  private volatile boolean running;

  public SecurityMaterialRefresher(List<FileBackedKeyRing> rings) {
    this.rings = List.copyOf(rings);
  }

  @Override
  public synchronized void start() {
    if (running) return;
    running = true;
    executor.scheduleWithFixedDelay(this::refresh, 30, 30, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void stop() {
    running = false;
    executor.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void refresh() {
    try {
      rings.forEach(FileBackedKeyRing::refresh);
    } catch (RuntimeException e) {
      LOG.atWarn()
          .addKeyValue("eventCode", "WEB_BFF_SECURITY_MATERIAL_REFRESH_FAILED")
          .log("Web BFF security material refresh failed");
    }
  }
}
