package com.fooddelivery.restaurant.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.restaurant.api.RestaurantDtos.*;
import com.fooddelivery.restaurant.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/manage")
public class MerchantController {
    private final RestaurantCatalog catalog;
    private final MenuCatalog menu;

    public record StaffInput(@NotBlank String userId,@NotBlank String role){}
    public record PriceChange(@NotNull @DecimalMin("0.01") BigDecimal price,
         @Min(0) long expectedVersion,@NotBlank String reason){}
    public record BlackoutInput(@NotNull Instant startsAtUtc,@NotNull Instant endsAtUtc,
                                 @Size(max=255) String reason){}

    @PostMapping("/brands/{brandId}/staff")
    public ApiResponse<Void> staff(@PathVariable("brandId") String brandId,
                 @Valid @RequestBody StaffInput input,Authentication actor) {
        catalog.addStaff(brandId,input.userId(),input.role(),actor);
        return ApiResponse.success(null,"Staff assigned");
    }
    @DeleteMapping("/brands/{brandId}/staff/{userId}")
    public ApiResponse<Void> removeStaff(@PathVariable("brandId") String brandId,
                    @PathVariable("userId") String userId,Authentication actor) {
        catalog.removeStaff(brandId,userId,actor);
        return ApiResponse.success(null,"Staff removed");
    }
    @PostMapping("/brands/{brandId}/outlets")
    public ResponseEntity<ApiResponse<OutletView>> outlet(@PathVariable("brandId") String brandId,
                    @Valid @RequestBody CreateOutlet input,Authentication actor) {
        if(!brandId.equals(input.brandId()))throw new com.fooddelivery.restaurant.exception.DomainException(
            HttpStatus.BAD_REQUEST,"BRAND_MISMATCH","Brand ID does not match route");
        return ResponseEntity.status(HttpStatus.CREATED)
          .body(ApiResponse.success(catalog.createOutlet(input,actor),"Outlet created"));
    }
    @PutMapping("/outlets/{outletId}/accepting-orders")
    public ApiResponse<OutletView> setAccepting(@PathVariable("outletId") String id,
                   @Valid @RequestBody OperatingInput req,Authentication actor) {
        return ApiResponse.success(catalog.acceptingOrders(id,req.acceptingOrders(),actor),"Updated");
    }
    @DeleteMapping("/outlets/{outletId}")
    public ApiResponse<Void> disableOutlet(@PathVariable("outletId") String id,Authentication actor){
        catalog.disableOutlet(id,actor);
        return ApiResponse.success(null,"Outlet disabled");
    }
    @PutMapping("/outlets/{outletId}/hours")
    public ApiResponse<Void> hours(@PathVariable("outletId") String id,
                   @Valid @RequestBody List<@Valid HoursInput> schedule,Authentication actor){
        catalog.setHours(id,schedule,actor);
        return ApiResponse.success(null,"Operating hours saved");
    }
    @PostMapping("/outlets/{outletId}/blackouts")
    public ApiResponse<Void> blackout(@PathVariable("outletId") String id,
                    @Valid @RequestBody BlackoutInput req,Authentication actor) {
        catalog.blackout(id,req.startsAtUtc(),req.endsAtUtc(),req.reason(),actor);
        return ApiResponse.success(null,"Blackout added");
    }
    @PostMapping("/brands/{brandId}/categories")
    public ResponseEntity<ApiResponse<CategoryView>> category(@PathVariable("brandId") String brandId,
                   @Valid @RequestBody CategoryInput req,Authentication actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
           .body(ApiResponse.success(menu.addCategory(brandId,req,actor),"Category created"));
    }
    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> disableCategory(@PathVariable("categoryId") String id,Authentication actor){
        menu.disableCategory(id,actor);
        return ApiResponse.success(null,"Category disabled");
    }
    @PostMapping("/items")
    public ResponseEntity<ApiResponse<ItemView>> item(@Valid @RequestBody ItemInput req,Authentication actor){
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(menu.addItem(req,actor),"Item created"));
    }
    @PutMapping("/items/{itemId}/price")
    public ApiResponse<ItemView> price(@PathVariable("itemId") String id,
           @Valid @RequestBody PriceChange req,Authentication actor) {
        return ApiResponse.success(menu.changePrice(id,req.price(),req.expectedVersion(),req.reason(),actor),
            "Price updated");
    }
    @DeleteMapping("/items/{itemId}")
    public ApiResponse<Void> disableItem(@PathVariable("itemId") String id,Authentication actor) {
        menu.disableItem(id,actor);
        return ApiResponse.success(null,"Item disabled");
    }
    @PutMapping("/outlets/{outletId}/items/{itemId}")
    public ApiResponse<Void> availability(@PathVariable("outletId") String outletId,
         @PathVariable("itemId") String itemId,@Valid @RequestBody StatusInput req,Authentication actor) {
        menu.overrideItem(outletId,itemId,req,actor);
        return ApiResponse.success(null,"Item availability updated");
    }
    @PostMapping("/brands/{brandId}/modifier-groups")
    public ResponseEntity<ApiResponse<GroupView>> group(@PathVariable("brandId") String brandId,
         @Valid @RequestBody ModifierGroupInput req,Authentication actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(menu.addGroup(brandId,req,actor),"Modifier group created"));
    }
    @PostMapping("/modifier-groups/{groupId}/modifiers")
    public ResponseEntity<ApiResponse<ModifierView>> modifier(@PathVariable("groupId") String groupId,
         @Valid @RequestBody ModifierInput req,Authentication actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(menu.addModifier(groupId,req,actor),"Modifier created"));
    }
    @PutMapping("/items/{itemId}/modifier-groups/{groupId}")
    public ApiResponse<Void> attach(@PathVariable("itemId") String itemId,
         @PathVariable("groupId") String groupId,Authentication actor) {
        menu.attachGroup(itemId,groupId,actor);
        return ApiResponse.success(null,"Modifier group linked");
    }
}
