package com.fooddelivery.restaurant.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

public final class RestaurantDtos {
    private RestaurantDtos() {}
    public record CreateBrand(
        @NotBlank @Size(max=100) String name,
        @Size(max=255) String logoUrl,
        @Size(max=20) String corporatePhone,
        @Size(max=50) String cuisineType,
        @NotBlank String ownerUserId) {}
    public record BrandView(String brandId,String name,String logoUrl,
        String corporatePhone,String cuisineType,boolean active) {}

    public record CreateOutlet(
        @NotBlank String brandId,
        @NotBlank @Size(max=100) String name,
        @NotBlank @Size(max=255) String street,
        @NotBlank @Size(max=255) String locality,
        @NotBlank @Size(max=100) String city,
        @NotBlank @Size(max=100) String state,
        @NotBlank @Size(max=20) String postalCode,
        @NotBlank @Size(max=100) String country,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotBlank String timezone,
        @Min(5) @Max(240) int prepMinutes,
        @NotNull @DecimalMin("0.1") @DecimalMax("100") BigDecimal deliveryRadiusKm) {}
    public record OutletView(String outletId,String brandId,String name,String street,String locality,
        String city,String state,String postalCode,String country,
        BigDecimal latitude,BigDecimal longitude,String timezone,int prepMinutes,
        BigDecimal deliveryRadiusKm,boolean isActive,boolean isAcceptingOrders,boolean isOpen,long version) {}

    public record CategoryInput(
        @NotBlank @Size(max=50) String name,@Min(0) @Max(100000) int displayOrder) {}
    public record CategoryView(String categoryId,String brandId,String name,int displayOrder,boolean active) {}
    public record ItemInput(@NotBlank String categoryId,@NotBlank @Size(max=100) String name,
        @Size(max=2000) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal basePrice,
        @Size(max=255) String imageUrl,boolean vegetarian,@Size(max=255) String allergens) {}
    public record ItemView(String itemId,String categoryId,String name,String description,
        BigDecimal basePrice,String imageUrl,boolean vegetarian,String allergens,
        boolean active,long version) {}
    public record StatusInput(@NotNull Status status,
        @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal overridePrice) {
        public enum Status { AVAILABLE, OUT_OF_STOCK, DISABLED }
    }
    public record HoursInput(@NotNull @Min(1) @Max(7) Integer dayOfWeek,
        @NotNull LocalTime opensAt,@NotNull LocalTime closesAt,boolean closed) {}
    public record OperatingInput(boolean acceptingOrders) {}
    public record ModifierGroupInput(@NotBlank @Size(max=100) String name,
        @Min(0) @Max(20) int minSelection,@Min(1) @Max(20) int maxSelection) {}
    public record ModifierInput(@NotBlank @Size(max=100) String name,
        @NotNull @DecimalMin("0.00") @Digits(integer=8,fraction=2) BigDecimal priceAdjustment) {}
    public record ModifierView(String modifierId,String groupId,String name,BigDecimal priceAdjustment) {}
    public record GroupView(String groupId,String brandId,String name,int minSelection,int maxSelection,
        List<ModifierView> modifiers) {}
    public record MenuItemView(ItemView item,BigDecimal effectivePrice,String availability,
        List<GroupView> modifierGroups) {}
    public record MenuView(OutletView outlet,List<MenuItemView> items) {}
    public record QuoteRequest(@NotEmpty @Size(max=100) List<@Valid QuoteLine> items) {}
    public record QuoteLine(@NotBlank String itemId,@Min(1) @Max(100) int quantity,
        @NotNull List<@NotBlank String> modifierIds) {}
    public record QuoteLineResult(String itemId,int quantity,BigDecimal unitPrice,
        BigDecimal lineTotal,List<String> modifierIds) {}
    public record QuoteResult(String outletId,String currency,List<QuoteLineResult> lines,
        BigDecimal itemsTotal,String priceDisclaimer) {}
    public record PageView<T>(List<T> content,int limit,int offset) {}
}
