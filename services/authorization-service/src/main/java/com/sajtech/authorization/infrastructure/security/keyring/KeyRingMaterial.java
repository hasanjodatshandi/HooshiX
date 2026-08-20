package com.sajtech.authorization.infrastructure.security.keyring;

import javax.crypto.SecretKey;

public record KeyRingMaterial(String keyId, SecretKey key) {}
