package com.sajtech.webbff.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.sajtech.webbff")
@EnableScheduling
public class WebBffApplication {
  public static void main(String[] args) {
    SpringApplication.run(WebBffApplication.class, args);
  }
}
