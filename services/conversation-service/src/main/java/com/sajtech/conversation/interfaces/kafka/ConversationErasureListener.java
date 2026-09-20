package com.sajtech.conversation.interfaces.kafka;

import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sajtech.conversation.infrastructure.erasure.JdbcConversationErasureRepository;
import com.sajtech.identity.contract.v1.ErasureCommandEvent;
import java.time.Clock;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

public final class ConversationErasureListener {
  private final JdbcConversationErasureRepository repository;
  private final Clock clock;

  public ConversationErasureListener(JdbcConversationErasureRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @KafkaListener(
      topics = "${conversation.erasure-command-topic:hooshix.identity.erasure.command.v1}",
      groupId = "${conversation.erasure-consumer-group:hooshix-conversation-erasure-v1}",
      containerFactory = "conversationKafkaListenerContainerFactory")
  public void receive(byte[] payload, Acknowledgment acknowledgment) {
    repository.receive(parse(payload), clock.instant());
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
