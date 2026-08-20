package com.sajtech.authorization.configuration;

import com.sajtech.authorization.infrastructure.security.IdentityJwtVerifier;
import com.sajtech.authorization.infrastructure.security.keyring.FileBackedKeyRing;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;

public final class SecurityMaterialRefresher implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(SecurityMaterialRefresher.class);
  private final FileBackedKeyRing intent, quota;
  private final IdentityJwtVerifier jwt;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("authorization-security-material-refresh").factory());
  private volatile boolean running;

  public SecurityMaterialRefresher(
      FileBackedKeyRing intent, FileBackedKeyRing quota, IdentityJwtVerifier jwt) {
    this.intent = intent;
    this.quota = quota;
    this.jwt = jwt;
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
      intent.refresh();
      quota.refresh();
      jwt.refresh();
    } catch (RuntimeException e) {
      LOG.atWarn()
          .addKeyValue("eventCode", "AUTHORIZATION_SECURITY_MATERIAL_REFRESH_FAILED")
          .log("Authorization security material refresh failed");
    }
  }
}
