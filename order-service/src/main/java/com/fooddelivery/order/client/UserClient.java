package com.fooddelivery.order.client;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.order.dto.external.UserDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// "user-service" is the application name registered in Eureka
@FeignClient(name = "USER-SERVICE")
public interface UserClient {

    @GetMapping("/api/v1/users/{userId}")
    ApiResponse<UserDto> getUser(@PathVariable("userId") String userId);
}