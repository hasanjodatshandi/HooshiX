package com.sajtech.identity.application.tenant.model;

import java.util.UUID;

public record RemovalPreparation(
    UUID requestId,
    UUID tenantId,
    UUID targetMembershipId,
    UUID actorMembershipId,
    boolean actorIsTarget) {}
