package com.fooddelivery.restaurant.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.restaurant.api.RestaurantDtos.*;
import com.fooddelivery.restaurant.service.RestaurantCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class BrandController {
    private final RestaurantCatalog catalog;

    @PostMapping("/api/v1/platform/brands")
    public ResponseEntity<ApiResponse<BrandView>> create(@Valid @RequestBody CreateBrand body) {
        return ResponseEntity.status(HttpStatus.CREATED)
           .body(ApiResponse.success(catalog.createBrand(body),"Brand created"));
    }
    @DeleteMapping("/api/v1/platform/brands/{brandId}")
    public ApiResponse<Void> disable(@PathVariable("brandId") String brandId) {
        catalog.deactivateBrand(brandId);
        return ApiResponse.success(null,"Brand disabled");
    }
    @GetMapping("/api/v1/brands/{brandId}")
    public ApiResponse<BrandView> get(@PathVariable("brandId") String id) {
        return ApiResponse.success(catalog.brand(id),"Brand found");
    }
    @GetMapping("/api/v1/brands")
    public ApiResponse<PageView<BrandView>> list(@RequestParam(name="limit",defaultValue="20") int limit,
                                      @RequestParam(name="offset",defaultValue="0") int offset) {
        return ApiResponse.success(catalog.brands(limit,offset),"Brands");
    }
}
