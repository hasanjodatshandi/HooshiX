package com.sajtech.identity.application.tenant.model;

import java.util.UUID;

public record AcceptedInvitation(UUID tenantId, UUID membershipId) {}
