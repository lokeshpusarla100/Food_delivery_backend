package com.fooddelivery.analytics;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class AnalyticsServiceApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
