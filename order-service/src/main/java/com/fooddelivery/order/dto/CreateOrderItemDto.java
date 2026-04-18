package com.fooddelivery.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateOrderItemDto {

    @NotBlank
    private String catalogItemId;

    @Min(1)
    private int quantity;

    @Valid
    private List<CreateOrderItemModifierDto> modifiers;

    @Size(max = 255)
    private String instructions;
}

