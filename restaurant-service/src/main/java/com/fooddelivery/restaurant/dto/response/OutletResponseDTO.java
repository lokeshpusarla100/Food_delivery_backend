package com.fooddelivery.restaurant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutletResponseDTO {
    private String outletId;
    private String brandId;

    // Address fields
    private String street;
    private String locality;
    private String city;
    private String state;
    private String postalCode;
    private String country;

    private String locationName;

    // Coordinates
    private BigDecimal latitude;
    private BigDecimal longitude;

    private Boolean isActive;
    private LocalDateTime createdAt;
}
