package com.fooddelivery.order.client;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.order.dto.external.UserDto;
import com.fooddelivery.internaluser.UserFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name="USER-SERVICE", configuration=UserFeignConfig.class)
public interface UserClient {
    @GetMapping("/api/v1/internal/users/{userId}")
    ApiResponse<UserDto> getUser(@PathVariable("userId") String userId);
}
