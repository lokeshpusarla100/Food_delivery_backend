package com.fooddelivery.order.service;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.order.client.RestaurantClient;
import com.fooddelivery.order.client.UserClient;
import com.fooddelivery.order.dto.CreateOrderRequestDto;
import com.fooddelivery.order.dto.OrderResponse;
import com.fooddelivery.order.dto.external.OutletDto;
import com.fooddelivery.order.dto.external.UserDto;
import com.fooddelivery.order.entity.Order;
import com.fooddelivery.order.entity.OrderItem;
import com.fooddelivery.order.mapper.OrderMapper;
import com.fooddelivery.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserClient userClient;
    private final RestaurantClient restaurantClient;
    private final OrderMapper orderMapper;
    private final PricingService pricingService;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequestDto request) {
        log.info("Creating order for user: {}", request.getUserId());

        // 1. Validate User
        ApiResponse<UserDto> userResponse = userClient.getUser(request.getUserId());
        if (!userResponse.isSuccess() || userResponse.getData() == null) {
            throw new RuntimeException("Invalid User ID: " + request.getUserId());
        }

        // 2. Validate Outlet
        ApiResponse<OutletDto> outletResponse = restaurantClient.getOutlet(request.getOutletId());
        if (!outletResponse.isSuccess() || outletResponse.getData() == null) {
            throw new RuntimeException("Invalid Outlet ID: " + request.getOutletId());
        }
        OutletDto outlet = outletResponse.getData();

        // 3. Build Order Entity using Mapper
        Order order = orderMapper.createOrder(request, outlet);
        List<OrderItem> orderItems = orderMapper.createOrderItems(request.getItems(), order);

        // 4. Calculate Totals using PricingService
        pricingService.calculateOrderTotals(order, orderItems);

        // Attach items to order so cascade persist works
        order.setItems(orderItems);

        // 5. Save Order
        Order savedOrder = orderRepository.save(order);

        log.info("Order created successfully: {}", savedOrder.getOrderId());
        return orderMapper.mapToResponse(savedOrder);
    }
}