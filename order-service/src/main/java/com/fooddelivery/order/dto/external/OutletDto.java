package com.fooddelivery.order.dto.external;


import lombok.Data;

@Data
public class OutletDto {
    private String outletId;
    private String name;
    private boolean isActive;
    private boolean isOpen;
}
