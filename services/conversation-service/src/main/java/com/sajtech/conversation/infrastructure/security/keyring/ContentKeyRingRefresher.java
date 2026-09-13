package com.sajtech.conversation.infrastructure.security.keyring;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class ContentKeyRingRefresher {
  private static final Logger LOG = LoggerFactory.getLogger(ContentKeyRingRefresher.class);
  private final FileBackedContentKeyRing keyRing;

  public ContentKeyRingRefresher(FileBackedContentKeyRing keyRing) {
    this.keyRing = Objects.requireNonNull(keyRing);
  }

  @Scheduled(fixedDelayString = "${conversation.key-ring-refresh-interval}")
  public void refresh() {
    try {
      keyRing.refresh();
    } catch (RuntimeException exception) {
      LOG.atWarn()
          .addKeyValue("event_code", "conversation_content_key_refresh_failed")
          .log("Conversation content key refresh failed");
    }
  }
}
