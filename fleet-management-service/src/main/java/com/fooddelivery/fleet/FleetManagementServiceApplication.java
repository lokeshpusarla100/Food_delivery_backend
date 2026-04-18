package com.fooddelivery.fleet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class FleetManagementServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FleetManagementServiceApplication.class, args);
    }
}
