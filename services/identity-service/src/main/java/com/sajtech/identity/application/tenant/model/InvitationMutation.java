package com.sajtech.identity.application.tenant.model;

import java.util.UUID;

public record InvitationMutation(UUID invitationId, String state) {}
