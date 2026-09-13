package com.sajtech.conversation.infrastructure.security.keyring;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class ContentKeyRingRefresherTest {
  @Test
  void refreshesAndContainsInvalidReplacementFailure() {
    FileBackedContentKeyRing ring = mock(FileBackedContentKeyRing.class);
    var refresher = new ContentKeyRingRefresher(ring);

    refresher.refresh();
    doThrow(new IllegalStateException("private test failure")).when(ring).refresh();
    refresher.refresh();

    verify(ring, times(2)).refresh();
  }
}
