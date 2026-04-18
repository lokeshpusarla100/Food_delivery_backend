package com.fooddelivery.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateOrderItemModifierDto {

    @NotBlank
    private String catalogModifierId;
}

