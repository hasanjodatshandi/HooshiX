package com.sajtech.conversation.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.sajtech.conversation")
@EnableScheduling
public class ConversationApplication {
  public static void main(String[] args) {
    SpringApplication.run(ConversationApplication.class, args);
  }
}
