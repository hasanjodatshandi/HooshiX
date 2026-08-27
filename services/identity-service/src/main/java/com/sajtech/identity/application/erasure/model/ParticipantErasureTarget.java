package com.sajtech.identity.application.erasure.model;

import java.util.List;
import java.util.UUID;

public record ParticipantErasureTarget(
    ErasureParticipant participant,
    UUID userId,
    List<UUID> notificationIds,
    String nextPageToken,
    boolean completePage) {
  public ParticipantErasureTarget {
    if (participant == null || notificationIds == null || nextPageToken == null) {
      throw new IllegalArgumentException("Participant erasure target is invalid");
    }
    notificationIds = List.copyOf(notificationIds);
  }
}
