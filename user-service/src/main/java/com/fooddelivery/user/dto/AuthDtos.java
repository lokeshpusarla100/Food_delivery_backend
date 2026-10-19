package com.fooddelivery.user.dto;

import jakarta.validation.constraints.*;

public final class AuthDtos {
    private AuthDtos() {}
    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max=512) String password,
                               @Size(max=128) String deviceId) {}
    public record RefreshRequest(@NotBlank @Size(max=512) String refreshToken) {}
    public record LogoutRequest(@NotBlank @Size(max=512) String refreshToken) {}
    public record PasswordChangeRequest(@NotBlank String currentPassword,
         @NotBlank @Size(min=12,max=72) String newPassword) {}
    public record ProfileUpdateRequest(@Size(max=100) String fullName,
         @Pattern(regexp="^\\+[1-9][0-9]{7,14}$",message="Use E.164 international phone format") String phoneNumber) {}
    public record AuthResponse(String accessToken,String refreshToken,String tokenType,
                               long expiresIn,UserResponse user) {}
    public record VerifyEmailRequest(@NotBlank String token) {}
}
