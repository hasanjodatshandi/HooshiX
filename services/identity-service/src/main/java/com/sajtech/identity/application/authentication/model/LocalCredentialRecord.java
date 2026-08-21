package com.sajtech.identity.application.authentication.model;

import java.util.UUID;

public record LocalCredentialRecord(UUID userId, String userStatus, String passwordHash) {}
