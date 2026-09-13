package com.sajtech.conversation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationPageTokenTest {
  @Test
  void roundTripsFixedWidthOpaqueCursor() {
    Instant activity = Instant.parse("2026-09-13T08:00:00.123456Z");
    UUID id = UUID.randomUUID();

    var decoded = ConversationPageToken.decode(ConversationPageToken.encode(activity, id));

    assertThat(decoded.activity()).isEqualTo(activity);
    assertThat(decoded.id()).isEqualTo(id);
  }

  @Test
  void rejectsMalformedOrWrongLengthTokens() {
    assertThatThrownBy(() -> ConversationPageToken.decode("%%%"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConversationPageToken.decode("YWJj"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
