package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.CanonicalContact;
import java.util.UUID;

public record ConfirmRegistrationCommand(
    UUID requestId, CanonicalContact contact, String code, String trustedClientIp) {}
