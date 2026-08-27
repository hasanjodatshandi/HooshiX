package com.sajtech.identity.infrastructure.worker;

import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.contract.v1.ErasureReceiptEvent;
import com.sajtech.identity.infrastructure.persistence.JooqErasureReceiptCoordinator;
import java.time.Clock;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

public final class ErasureReceiptListener {
  private final JooqErasureReceiptCoordinator coordinator;
  private final TransactionRunner transactions;
  private final Clock clock;

  public ErasureReceiptListener(
      JooqErasureReceiptCoordinator coordinator, TransactionRunner transactions, Clock clock) {
    this.coordinator = coordinator;
    this.transactions = transactions;
    this.clock = clock;
  }

  @KafkaListener(
      topics = "${identity.erasure-receipt-topic:hooshix.identity.erasure.receipt.v1}",
      groupId = "${identity.erasure-receipt-consumer-group:hooshix-identity-erasure-receipt-v1}",
      containerFactory = "erasureKafkaListenerContainerFactory")
  public void receive(byte[] payload, Acknowledgment acknowledgment) {
    ErasureReceiptEvent event = parse(payload);
    transactions.required(
        () -> {
          coordinator.receive(event, clock.instant());
          return null;
        });
    acknowledgment.acknowledge();
  }

  private static ErasureReceiptEvent parse(byte[] payload) {
    try {
      ErasureReceiptEvent event = ErasureReceiptEvent.parseFrom(payload);
      if (!ValidatorFactory.newBuilder().build().validate(event).isSuccess()) {
        throw new IllegalArgumentException("Erasure receipt contract is invalid");
      }
      return event;
    } catch (InvalidProtocolBufferException | ValidationException exception) {
      throw new IllegalArgumentException("Erasure receipt contract is invalid", exception);
    }
  }
}
