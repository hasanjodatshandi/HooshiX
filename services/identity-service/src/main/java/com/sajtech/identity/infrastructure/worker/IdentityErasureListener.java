package com.sajtech.identity.infrastructure.worker;

import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.contract.v1.ErasureCommandEvent;
import com.sajtech.identity.infrastructure.persistence.JooqIdentityErasureParticipant;
import java.time.Clock;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

public final class IdentityErasureListener {
  private final JooqIdentityErasureParticipant repository;
  private final TransactionRunner transactions;
  private final Clock clock;

  public IdentityErasureListener(
      JooqIdentityErasureParticipant repository, TransactionRunner transactions, Clock clock) {
    this.repository = repository;
    this.transactions = transactions;
    this.clock = clock;
  }

  @KafkaListener(
      topics = "${identity.erasure-command-topic:hooshix.identity.erasure.command.v1}",
      groupId = "${identity.erasure-consumer-group:hooshix-identity-erasure-v1}",
      containerFactory = "erasureKafkaListenerContainerFactory")
  public void receive(byte[] payload, Acknowledgment acknowledgment) {
    ErasureCommandEvent event = parse(payload);
    transactions.required(
        () -> {
          repository.receive(event, clock.instant());
          return null;
        });
    acknowledgment.acknowledge();
  }

  private static ErasureCommandEvent parse(byte[] payload) {
    try {
      ErasureCommandEvent event = ErasureCommandEvent.parseFrom(payload);
      if (!ValidatorFactory.newBuilder().build().validate(event).isSuccess()) {
        throw new IllegalArgumentException("Erasure event contract is invalid");
      }
      return event;
    } catch (InvalidProtocolBufferException | ValidationException exception) {
      throw new IllegalArgumentException("Erasure event contract is invalid", exception);
    }
  }
}
