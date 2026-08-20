package com.sajtech.webbff.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sajtech.webbff")
public class WebBffApplication {
  public static void main(String[] args) {
    SpringApplication.run(WebBffApplication.class, args);
  }
}
