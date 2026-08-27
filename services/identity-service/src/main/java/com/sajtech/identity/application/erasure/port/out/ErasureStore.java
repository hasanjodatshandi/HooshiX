package com.sajtech.identity.application.erasure.port.out;

import com.sajtech.identity.application.erasure.model.ErasureParticipant;
import com.sajtech.identity.application.erasure.model.ErasureRequestView;
import com.sajtech.identity.application.erasure.model.LegalHoldView;
import com.sajtech.identity.application.erasure.model.ParticipantErasureTarget;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ErasureStore {
  Optional<ErasureRequestView> find(UUID erasureRequestId);

  ErasureRequestView accept(UUID erasureRequestId, UUID userId, Instant now);

  ParticipantErasureTarget beginParticipant(
      UUID eventId,
      UUID erasureRequestId,
      ErasureParticipant participant,
      String participantPolicyVersion,
      String pageToken,
      Instant now);

  LegalHoldView createHold(
      UUID holdId, UUID erasureRequestId, String authorityReference, UUID actorUserId, Instant now);

  LegalHoldView releaseHold(UUID holdId, UUID actorUserId, Instant now);
}
