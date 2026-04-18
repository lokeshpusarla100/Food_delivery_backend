package com.fooddelivery.user;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class UserServiceApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(UserServiceApplication.class, args);
    }
}
