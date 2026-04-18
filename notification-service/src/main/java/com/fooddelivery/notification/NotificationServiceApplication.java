package com.fooddelivery.notification;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class NotificationServiceApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
