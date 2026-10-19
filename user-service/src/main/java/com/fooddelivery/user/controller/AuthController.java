package com.fooddelivery.user.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.user.dto.*;
import com.fooddelivery.user.dto.AuthDtos.*;
import com.fooddelivery.user.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserService users;
    private final AuthService auth;
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody UserRegistrationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
            users.registerUser(req),"Registration successful; verify your email"));
    }
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        return ApiResponse.success(auth.login(req,request.getRemoteAddr()),"Authenticated");
    }
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.success(auth.refresh(req),"Token refreshed");
    }
    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication principal,@Valid @RequestBody LogoutRequest req) {
        auth.logout(principal.getName(),req);
        return ApiResponse.success(null,"Logged out");
    }
    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll(Authentication principal) {
        auth.logoutAll(principal.getName());
        return ApiResponse.success(null,"All sessions revoked");
    }
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(Authentication principal,@Valid @RequestBody PasswordChangeRequest req) {
        auth.changePassword(principal.getName(),req);
        return ApiResponse.success(null,"Password changed; all sessions revoked");
    }
    @PostMapping("/verify-email/request")
    public ApiResponse<Void> requestVerification(Authentication principal) {
        auth.requestEmailVerification(principal.getName());
        return ApiResponse.success(null,"If required, verification email sent");
    }
    @PostMapping("/verify-email")
    public ApiResponse<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        auth.verifyEmail(req);
        return ApiResponse.success(null,"Email verified");
    }
    public record ResetRequest(@NotBlank @Email String email) {}
    public record ResetConfirm(@NotBlank String token,
             @NotBlank @Size(min=12,max=72) String newPassword) {}
    @PostMapping("/password-reset/request")
    public ResponseEntity<ApiResponse<Void>> passwordResetRequest(@Valid @RequestBody ResetRequest req) {
        auth.requestPasswordReset(req.email());
        return ResponseEntity.accepted().body(ApiResponse.success(null,
            "If the account exists, an email will be sent"));
    }
    @PostMapping("/password-reset/confirm")
    public ApiResponse<Void> passwordResetConfirm(@Valid @RequestBody ResetConfirm req) {
        auth.confirmPasswordReset(req.token(),req.newPassword());
        return ApiResponse.success(null,"Password reset; all sessions revoked");
    }
}
