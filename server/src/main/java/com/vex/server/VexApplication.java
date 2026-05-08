package com.vex.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Spring Boot entry point for the Vex server. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VexApplication {

  /** Boots the embedded Tomcat + Spring application. */
  public static void main(String[] args) {
    SpringApplication.run(VexApplication.class, args);
  }
}
