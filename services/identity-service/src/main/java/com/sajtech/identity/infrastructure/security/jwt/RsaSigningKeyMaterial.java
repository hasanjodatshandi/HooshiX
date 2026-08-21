package com.sajtech.identity.infrastructure.security.jwt;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

public record RsaSigningKeyMaterial(
    String keyId, RSAPrivateKey privateKey, RSAPublicKey publicKey) {}
