package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;

public record DecryptedHandoff(
    RegistrationChannel channel, String recipient, RegistrationLocale locale, String code) {}
