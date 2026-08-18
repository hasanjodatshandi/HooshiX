package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.CanonicalContact;
import com.sajtech.identity.domain.registration.RegistrationLocale;
import com.sajtech.identity.domain.registration.RegistrationProfile;
import java.util.UUID;

public record RegisterLocalCommand(
    UUID requestId,
    CanonicalContact contact,
    RegistrationProfile profile,
    String password,
    RegistrationLocale locale,
    String trustedClientIp) {}
