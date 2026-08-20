package com.sajtech.webbff.infrastructure.security.keyring;

import javax.crypto.SecretKey;

public record KeyRingMaterial(String keyId, SecretKey key) {}
