package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.CanonicalContact;
import java.util.UUID;

public record ResendRegistrationCommand(UUID requestId, CanonicalContact contact, String trustedClientIp) {}
