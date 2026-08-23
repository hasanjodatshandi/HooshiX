package com.sajtech.identity.application.profile.model;

import java.util.UUID;

public record ProfileUpdateCommand(
    UUID userId, String firstName, String lastName, String fatherName) {}
