package com.github.safetytrick.sqlexplorer.devidp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** A dummy OIDC identity provider for development and e2e. Never deploy it. */
@SpringBootApplication
@EnableConfigurationProperties(DevIdpProperties.class)
public class DevIdpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevIdpApplication.class, args);
    }
}
