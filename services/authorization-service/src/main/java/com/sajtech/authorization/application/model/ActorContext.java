package com.sajtech.authorization.application.model;

import java.util.UUID;

public record ActorContext(UUID userId, UUID tenantId, UUID membershipId, String sessionId) {}
