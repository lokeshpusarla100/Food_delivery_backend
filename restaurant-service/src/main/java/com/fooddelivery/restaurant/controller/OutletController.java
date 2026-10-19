package com.fooddelivery.restaurant.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.restaurant.api.RestaurantDtos.*;
import com.fooddelivery.restaurant.service.RestaurantCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/outlets")
public class OutletController {
    private final RestaurantCatalog catalog;
    // The path form matches Order Service's existing Feign contract.
    @GetMapping("/{outletId}")
    public ApiResponse<OutletView> byId(@PathVariable("outletId") String id) {
        return ApiResponse.success(catalog.outlet(id),"Outlet found");
    }
    @GetMapping
    public ApiResponse<OutletView> legacyByQuery(@RequestParam("outletId") String id) {
        return byId(id);
    }
    @GetMapping("/{outletId}/validate")
    public ApiResponse<Boolean> validate(@PathVariable("outletId") String id) {
        return ApiResponse.success(catalog.outletExists(id),"Outlet validation");
    }
    @GetMapping("/near")
    public ApiResponse<PageView<OutletView>> nearby(
            @RequestParam("lat") BigDecimal latitude,@RequestParam("lon") BigDecimal longitude,
            @RequestParam(name="radiusKm",defaultValue="5") BigDecimal radius,
            @RequestParam(name="limit",defaultValue="20") int limit) {
        return ApiResponse.success(catalog.near(latitude,longitude,radius,limit),"Nearby outlets");
    }
    @GetMapping("/search")
    public ApiResponse<PageView<OutletView>> byCity(@RequestParam("city") String city,
            @RequestParam(name="limit",defaultValue="20") int limit,
            @RequestParam(name="offset",defaultValue="0") int offset) {
        return ApiResponse.success(catalog.byCity(city,limit,offset),"Outlets by city");
    }
    @GetMapping("/brand/{brandId}")
    public ApiResponse<PageView<OutletView>> byBrand(@PathVariable("brandId") String brandId,
            @RequestParam(name="limit",defaultValue="20") int limit,
            @RequestParam(name="offset",defaultValue="0") int offset) {
        return ApiResponse.success(catalog.outletsForBrand(brandId,limit,offset),"Brand outlets");
    }
}
