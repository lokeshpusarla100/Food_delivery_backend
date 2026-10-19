package com.fooddelivery.restaurant.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.restaurant.api.RestaurantDtos.*;
import com.fooddelivery.restaurant.service.MenuCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
public class MenuController {
    private final MenuCatalog catalog;
    @GetMapping("/api/v1/menus/{outletId}")
    public ApiResponse<MenuView> get(@PathVariable("outletId") String id,
            @RequestParam(name="limit",defaultValue="50") int limit,
            @RequestParam(name="offset",defaultValue="0") int offset) {
        return ApiResponse.success(catalog.menu(id,limit,offset),"Outlet menu");
    }
    @GetMapping("/api/v1/menus/brands/{brandId}/categories")
    public ApiResponse<List<CategoryView>> categories(@PathVariable("brandId") String id) {
        return ApiResponse.success(catalog.categories(id),"Menu categories");
    }
    @PostMapping("/api/v1/internal/outlets/{outletId}/quote")
    public ApiResponse<QuoteResult> quote(@PathVariable("outletId") String id,
            @Valid @RequestBody QuoteRequest request) {
        return ApiResponse.success(catalog.quote(id,request),"Catalog quote");
    }
}
