package com.fooddelivery.user.dto;

import jakarta.validation.constraints.*;

public final class PreferencesDtos {
    private PreferencesDtos() {}
    public record PreferencesRequest(
        boolean emailNotifications,boolean smsNotifications, boolean pushNotifications,
        @NotBlank @Size(max=10) String language,
        @NotBlank @Pattern(regexp="light|dark|auto") String theme,
        @NotBlank @Pattern(regexp="[A-Z]{3}") String currency,
        boolean marketingEmails,boolean dataSharingConsent) {}
    public record PreferencesResponse(boolean emailNotifications,boolean smsNotifications,
        boolean pushNotifications,String language,String theme,String currency,
        boolean marketingEmails,boolean dataSharingConsent) {}
}
