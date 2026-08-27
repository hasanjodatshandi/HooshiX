package com.sajtech.identity.application.erasure.port.in;

import com.sajtech.identity.application.erasure.model.ErasureParticipant;
import com.sajtech.identity.application.erasure.model.ParticipantErasureTarget;
import java.util.UUID;

public interface ParticipantErasureCoordination {
  ParticipantErasureTarget begin(
      UUID eventId,
      UUID erasureRequestId,
      ErasureParticipant participant,
      String participantPolicyVersion,
      String pageToken,
      String authenticatedWorkload);
}
