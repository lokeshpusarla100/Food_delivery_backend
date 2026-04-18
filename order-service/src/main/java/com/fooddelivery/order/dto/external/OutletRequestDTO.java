package com.fooddelivery.order.dto.external;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutletRequestDTO {
    private String brandId;
    private String street;
    private String locality;
    private String city;
    private String state;
    private String postalCode;
    @Builder.Default
    private String country = "India";
    private String locationName;
    private BigDecimal latitude;
    private BigDecimal longitude;
}
