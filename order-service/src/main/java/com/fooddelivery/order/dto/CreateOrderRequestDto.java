package com.fooddelivery.order.dto;

import com.fooddelivery.order.entity.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateOrderRequestDto{

    @NotBlank
    private String userId;

    @NotBlank
    private String outletId;

    @NotNull
    private Order.OrderType orderType; // ASAP / SCHEDULED

    private LocalDateTime scheduledFor; // required if SCHEDULED

    @NotNull
    @Valid
    private DeliveryAddressDto deliveryAddress;

    @Size(max = 500)
    private String instructions;

    @NotEmpty
    @Valid
    private List<CreateOrderItemDto> items;

    private String promoCode;

    /**
     * Used for retry-safe order creation
     * (mobile retries, network failures, etc.)
     */
    @Size(max = 128)
    private String idempotencyKey;
}
