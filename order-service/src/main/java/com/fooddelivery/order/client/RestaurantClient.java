package com.fooddelivery.order.client;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.order.dto.external.OutletDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// "restaurant-service" is the application name registered in Eureka
@FeignClient(name = "RESTAURANT-SERVICE")
public interface RestaurantClient {

    // You will need to implement this endpoint in the Restaurant Service later
    @GetMapping("/api/v1/outlets/{outletId}")
    ApiResponse<OutletDto> getOutlet(@PathVariable("outletId") String outletId);

    // Example of another useful endpoint
    @GetMapping("/api/v1/outlets/{outletId}/validate")
    ApiResponse<Boolean> validateOutlet(@PathVariable("outletId") String outletId);

}