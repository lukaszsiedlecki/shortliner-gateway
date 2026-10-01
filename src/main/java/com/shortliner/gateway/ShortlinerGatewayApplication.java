package com.shortliner.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ShortlinerGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortlinerGatewayApplication.class, args);
    }
}
