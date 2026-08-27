package com.sajtech.webbff.infrastructure.erasure;

import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sajtech.identity.contract.v1.ErasureCommandEvent;
import java.time.Clock;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;

public final class WebBffErasureListener {
  private final JooqWebBffErasureRepository repository;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public WebBffErasureListener(
      JooqWebBffErasureRepository repository, TransactionTemplate transactions, Clock clock) {
    this.repository = repository;
    this.transactions = transactions;
    this.clock = clock;
  }

  @KafkaListener(
      topics = "${web-bff.erasure-command-topic:hooshix.identity.erasure.command.v1}",
      groupId = "${web-bff.erasure-consumer-group:hooshix-web-bff-erasure-v1}",
      containerFactory = "erasureKafkaListenerContainerFactory")
  public void receive(byte[] payload, Acknowledgment acknowledgment) {
    ErasureCommandEvent event = parse(payload);
    transactions.executeWithoutResult(ignored -> repository.receive(event, clock.instant()));
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
