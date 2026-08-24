package com.sajtech.identity.application.password.port.in;
public record ConfirmPasswordRecoveryCommand(String contact,String code,String newPassword) {}
