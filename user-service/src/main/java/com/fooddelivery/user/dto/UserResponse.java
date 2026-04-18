package com.fooddelivery.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private String userId;
    private String email;
    private String phoneNumber;
    private String fullName;
    private boolean isVerified;
    private boolean isActive;
    private LocalDateTime createdAt;
}
