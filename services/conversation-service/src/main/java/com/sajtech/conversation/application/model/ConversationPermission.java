package com.sajtech.conversation.application.model;

public enum ConversationPermission {
  CREATE("conversation.create"),
  READ("conversation.read"),
  GENERATE("conversation.generate"),
  DELETE("conversation.delete");

  private final String key;

  ConversationPermission(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
