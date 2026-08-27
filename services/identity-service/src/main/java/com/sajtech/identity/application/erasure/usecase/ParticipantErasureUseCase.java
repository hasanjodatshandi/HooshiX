package com.sajtech.identity.application.erasure.usecase;

import com.sajtech.identity.application.erasure.ErasureError;
import com.sajtech.identity.application.erasure.ErasureException;
import com.sajtech.identity.application.erasure.model.ErasureParticipant;
import com.sajtech.identity.application.erasure.model.ParticipantErasureTarget;
import com.sajtech.identity.application.erasure.port.in.ParticipantErasureCoordination;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

public final class ParticipantErasureUseCase implements ParticipantErasureCoordination {
  private final ErasureStore erasure;
  private final TransactionRunner transactions;
  private final Clock clock;

  public ParticipantErasureUseCase(
      ErasureStore erasure, TransactionRunner transactions, Clock clock) {
    this.erasure = erasure;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public ParticipantErasureTarget begin(
      UUID eventId,
      UUID erasureRequestId,
      ErasureParticipant participant,
      String participantPolicyVersion,
      String pageToken,
      String authenticatedWorkload) {
    if (eventId == null
        || erasureRequestId == null
        || participant == null
        || participantPolicyVersion == null
        || pageToken == null
        || authenticatedWorkload == null) {
      throw new ErasureException(ErasureError.INVALID_ARGUMENT, "Participant request is invalid");
    }
    String expected = participant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    if (!expected.equals(authenticatedWorkload)) {
      throw new ErasureException(ErasureError.FORBIDDEN, "Participant workload is forbidden");
    }
    return transactions.required(
        () ->
            erasure.beginParticipant(
                eventId,
                erasureRequestId,
                participant,
                participantPolicyVersion,
                pageToken,
                clock.instant()));
  }
}
