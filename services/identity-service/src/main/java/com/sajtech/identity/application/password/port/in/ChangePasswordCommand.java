package com.sajtech.identity.application.password.port.in;
public record ChangePasswordCommand(String refreshCredential,String currentPassword,String newPassword) {}
