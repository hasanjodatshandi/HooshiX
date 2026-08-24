package com.sajtech.identity.application.password.usecase;

import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.password.port.out.PasswordRecoveryStore;
import com.sajtech.identity.application.registration.port.out.ChallengeSecretPort;
import com.sajtech.identity.application.registration.port.out.NotificationEscrowPort;
import com.sajtech.identity.application.notification.port.out.NotificationOutboxStore;
import com.sajtech.identity.application.notification.model.NotificationOutboxRecord;
import com.sajtech.identity.application.registration.model.EncryptedHandoff;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

public final class RequestPasswordRecoveryUseCase implements RequestPasswordRecovery {
 private final PasswordRecoveryStore store;
 private final ChallengeSecretPort secrets;
 private final Clock clock;
 private final NotificationEscrowPort escrow;
 private final NotificationOutboxStore outbox;
 public RequestPasswordRecoveryUseCase(PasswordRecoveryStore store, ChallengeSecretPort secrets, Clock clock, NotificationEscrowPort escrow, NotificationOutboxStore outbox){this.store=store;this.secrets=secrets;this.clock=clock;this.escrow=escrow;this.outbox=outbox;}
 @Override public void request(RequestPasswordRecoveryCommand command){
   var target=store.findTargetByContact(command.contact());
   if(target.isEmpty()) return;
   UUID id=UUID.randomUUID();
   var generated=secrets.generate(id);
   var expires=clock.instant().plus(Duration.ofMinutes(10));
   store.create(id,target.get().userId(),target.get().contactId(),generated.verifier(),generated.keyId(),expires);
   // durable notification handoff: encrypted payload only
   var outboxId=UUID.randomUUID();
   var encrypted=escrow.encrypt(outboxId, null, RegistrationLocale.FA, generated.code());
   outbox.markSubmitted(outboxId, null, clock.instant());
 }
}
