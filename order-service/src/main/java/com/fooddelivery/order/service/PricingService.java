package com.fooddelivery.order.service;

import com.fooddelivery.order.entity.Order;
import com.fooddelivery.order.entity.OrderItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PricingService {

    private static final BigDecimal TAX_RATE = BigDecimal.valueOf(0.05);
    private static final BigDecimal FIXED_DELIVERY_FEE = BigDecimal.valueOf(40);

    public void calculateOrderTotals(Order order, List<OrderItem> orderItems) {
        BigDecimal itemsTotal = BigDecimal.ZERO;

        for (OrderItem item : orderItems) {
            itemsTotal = itemsTotal.add(item.getItemsLineTotal());
        }

        order.setItemsTotal(itemsTotal);
        order.setTax(itemsTotal.multiply(TAX_RATE));
        order.setDeliveryFee(FIXED_DELIVERY_FEE);
        order.setTotalAmount(order.getItemsTotal().add(order.getTax()).add(order.getDeliveryFee()));
        order.setSubtotal(itemsTotal);
    }
}
