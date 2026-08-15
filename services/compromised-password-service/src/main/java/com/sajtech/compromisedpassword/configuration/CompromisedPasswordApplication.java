package com.sajtech.compromisedpassword.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackageClasses = RuntimeConfiguration.class)
public class CompromisedPasswordApplication {
  public static void main(String[] args) {
    SpringApplication.run(CompromisedPasswordApplication.class, args);
  }
}
