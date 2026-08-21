package com.sajtech.identity.application.registration.port.out;

import com.sajtech.identity.application.registration.model.DecryptedHandoff;
import com.sajtech.identity.application.registration.model.EncryptedHandoff;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.util.UUID;

public interface NotificationEscrowPort {
  EncryptedHandoff encrypt(
      UUID outboxId, CanonicalContact contact, RegistrationLocale locale, String code);

  DecryptedHandoff decrypt(UUID outboxId, String keyId, byte[] nonce, byte[] ciphertext);
}
