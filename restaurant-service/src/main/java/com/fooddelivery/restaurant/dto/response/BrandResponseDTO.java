package com.fooddelivery.restaurant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandResponseDTO {
    private String brandId;
    private String name;
    private String logoUrl;
    private String corporatePhone;
    private String cuisineType;
    private LocalDateTime createdAt;
}
