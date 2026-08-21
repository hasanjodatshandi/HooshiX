package com.sajtech.webbff.application.model;

public record BrowserSessionGrant(String cookieValue, String csrfToken, BrowserSession session) {}
