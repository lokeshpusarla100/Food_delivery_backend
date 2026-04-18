package com.fooddelivery.restaurant;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class RestaurantServiceApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(RestaurantServiceApplication.class, args);
    }
}
