package com.sajtech.identity.application.password.usecase;

import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.password.port.out.PasswordRecoveryStore;
import com.sajtech.identity.application.registration.port.out.ChallengeSecretPort;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

public final class RequestPasswordRecoveryUseCase implements RequestPasswordRecovery {
 private final PasswordRecoveryStore store;
 private final ChallengeSecretPort secrets;
 private final Clock clock;
 public RequestPasswordRecoveryUseCase(PasswordRecoveryStore store, ChallengeSecretPort secrets, Clock clock){this.store=store;this.secrets=secrets;this.clock=clock;}
 @Override public void request(RequestPasswordRecoveryCommand command){
   var target=store.findTargetByContact(command.contact());
   if(target.isEmpty()) return;
   UUID id=UUID.randomUUID();
   var generated=secrets.generate(id);
   store.create(id,target.get().userId(),target.get().contactId(),generated.verifier(),generated.keyId(),clock.instant().plus(Duration.ofMinutes(10)));
 }
}
