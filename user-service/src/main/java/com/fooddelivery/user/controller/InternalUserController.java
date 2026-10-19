package com.fooddelivery.user.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.user.dto.UserResponse;
import com.fooddelivery.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/internal/users")
public class InternalUserController {
    private final UserService users;
    @GetMapping("/{userId}")
    public ApiResponse<UserResponse> get(@PathVariable("userId") String userId) {
        return ApiResponse.success(users.getUserById(userId),"User fetched");
    }
}
