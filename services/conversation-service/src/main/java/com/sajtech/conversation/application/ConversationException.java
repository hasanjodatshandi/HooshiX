package com.sajtech.conversation.application;

public final class ConversationException extends RuntimeException {
  private final ConversationError error;

  public ConversationException(ConversationError error, String message) {
    super(message);
    this.error = error;
  }

  public ConversationException(ConversationError error, String message, Throwable cause) {
    super(message, cause);
    this.error = error;
  }

  public ConversationError error() {
    return error;
  }
}
