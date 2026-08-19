package com.sajtech.authorization.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sajtech.authorization")
public class AuthorizationApplication {
  public static void main(String[] args) {
    SpringApplication.run(AuthorizationApplication.class, args);
  }
}
