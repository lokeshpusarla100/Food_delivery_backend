package com.fooddelivery.user.dto;

import jakarta.validation.constraints.*;

public final class AddressDtos {
    private AddressDtos() {}
    public record AddressRequest(
        @NotBlank @Size(max=50) String label,
        @NotBlank @Size(max=255) String streetLine1,
        @Size(max=255) String streetLine2,
        @NotBlank @Size(max=255) String locality,
        @NotBlank @Size(max=100) String city,
        @NotBlank @Size(max=100) String state,
        @NotBlank @Size(max=20) String zipCode,
        @NotBlank @Size(max=100) String country,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        boolean makeDefault) {}
    public record AddressResponse(long addressId, String label, String streetLine1, String streetLine2,
        String locality, String city, String state, String zipCode, String country,
        double latitude, double longitude, boolean isDefault) {}
}
