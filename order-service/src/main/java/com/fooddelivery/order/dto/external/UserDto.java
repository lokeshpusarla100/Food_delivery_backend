package com.fooddelivery.order.dto.external;


import lombok.Data;

@Data
public class UserDto {
    private String userId;
    private String email;
    private String fullName;
    private boolean isVerified;
}
