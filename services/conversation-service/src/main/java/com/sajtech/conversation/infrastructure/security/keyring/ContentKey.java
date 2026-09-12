package com.sajtech.conversation.infrastructure.security.keyring;

import javax.crypto.SecretKey;

public record ContentKey(String keyId, SecretKey key) {}
