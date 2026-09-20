package com.sajtech.conversation.interfaces.kafka;

import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sajtech.conversation.infrastructure.lifecycle.JdbcTenantLifecycleRepository;
import com.sajtech.identity.contract.v1.TenantLifecycleEvent;
import java.time.Clock;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

public final class TenantLifecycleListener {
  private final JdbcTenantLifecycleRepository repository;
  private final Clock clock;

  public TenantLifecycleListener(JdbcTenantLifecycleRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @KafkaListener(
      topics = "${conversation.tenant-lifecycle-topic:hooshix.identity.tenant.lifecycle.v1}",
      groupId =
          "${conversation.tenant-lifecycle-consumer-group:hooshix-conversation-tenant-lifecycle-v1}",
      containerFactory = "conversationKafkaListenerContainerFactory")
  public void receive(byte[] payload, Acknowledgment acknowledgment) {
    repository.receive(parse(payload), clock.instant());
    acknowledgment.acknowledge();
  }

  private static TenantLifecycleEvent parse(byte[] payload) {
    try {
      TenantLifecycleEvent event = TenantLifecycleEvent.parseFrom(payload);
      if (!ValidatorFactory.newBuilder().build().validate(event).isSuccess()) {
        throw new IllegalArgumentException("Tenant lifecycle event contract is invalid");
      }
      return event;
    } catch (InvalidProtocolBufferException | ValidationException exception) {
      throw new IllegalArgumentException("Tenant lifecycle event contract is invalid", exception);
    }
  }
}
