package com.sajtech.identity.application.tenant.model;

import java.time.Instant;
import java.util.UUID;

public record InvitationResult(UUID invitationId, Instant expiresAt) {}
