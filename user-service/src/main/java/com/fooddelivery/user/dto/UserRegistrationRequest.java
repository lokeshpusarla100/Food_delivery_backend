package com.fooddelivery.user.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserRegistrationRequest {
    @NotBlank @Email @Size(max=255)
    private String email;
    @NotBlank @Pattern(regexp="^\\+[1-9][0-9]{7,14}$",message="Use E.164 international phone format")
    private String phoneNumber;
    @NotBlank @Size(min=12,max=72)
    private String password;
    @NotBlank @Size(max=100)
    private String fullName;
}
