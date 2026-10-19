package com.fooddelivery.user.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.user.dto.*;
import com.fooddelivery.user.dto.AuthDtos.ProfileUpdateRequest;
import com.fooddelivery.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService users;
    @GetMapping("/me")
    public ApiResponse<UserResponse> me(Authentication principal) {
        return ApiResponse.success(users.getUserById(principal.getName()),"Profile retrieved");
    }
    @PatchMapping("/me")
    public ApiResponse<UserResponse> update(Authentication principal,
              @Valid @RequestBody ProfileUpdateRequest req) {
        return ApiResponse.success(users.updateMyProfile(principal.getName(),req),"Profile updated");
    }
    public record DeactivateRequest(String password) {}
    @PostMapping("/me/deactivate")
    public ApiResponse<Void> deactivate(Authentication principal,@RequestBody DeactivateRequest req) {
        users.deactivate(principal.getName(),req.password());
        return ApiResponse.success(null,"Account deactivated");
    }
    // Legacy lookups are strictly service-to-service, never available to customer JWTs.
    @GetMapping("/get-by-id")
    public ApiResponse<UserResponse> getById(@RequestParam String userId) {
        return ApiResponse.success(users.getUserById(userId),"User fetched");
    }
    @GetMapping("/get-by-email")
    public ApiResponse<UserResponse> getByEmail(@RequestParam String email) {
        return ApiResponse.success(users.getUserByEmail(email),"User fetched");
    }
}
