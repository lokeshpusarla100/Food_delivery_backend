package com.fooddelivery.restaurant.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandRequestDTO {

    @NotBlank(message = "Brand name is required")
    @Size(max = 100, message = "Brand name must be less than 100 characters")
    private String name;

    @Size(max = 255, message = "Logo URL must be less than 255 characters")
    private String logoUrl;

    @Size(max = 20, message = "Corporate phone must be less than 20 characters")
    private String corporatePhone;

    @Size(max = 50, message = "Cuisine type must be less than 50 characters")
    private String cuisineType;
}
