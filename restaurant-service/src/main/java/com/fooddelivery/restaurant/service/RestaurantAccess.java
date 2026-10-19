package com.fooddelivery.restaurant.service;

import com.fooddelivery.restaurant.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class RestaurantAccess {
    private final JdbcTemplate jdbc;
    public String user(Authentication principal) {
        if(!(principal instanceof JwtAuthenticationToken))
            throw new DomainException(HttpStatus.FORBIDDEN,"MERCHANT_JWT_REQUIRED","Merchant login required");
        return principal.getName();
    }
    public void requireStaff(String brandId,Authentication principal,boolean ownerOnly) {
        String userId=user(principal);
        Integer count=jdbc.queryForObject(
            "SELECT COUNT(*) FROM restaurant_staff s JOIN brands b ON b.brand_id=s.brand_id "
                +"WHERE s.brand_id=? AND s.user_id=? AND b.is_active=TRUE "
                +(ownerOnly?"AND s.role='OWNER'":"AND s.role IN ('OWNER','MANAGER','EDITOR')"),
            Integer.class,brandId,userId);
        if(count==null || count==0)
            throw new DomainException(HttpStatus.FORBIDDEN,"FORBIDDEN","Not authorized for this brand");
    }
    public String requireOutlet(String outletId,Authentication principal) {
        String brandId=jdbc.query("SELECT brand_id FROM outlets WHERE outlet_id=? AND is_active=TRUE",
            (rs,n)->rs.getString(1),outletId).stream().findFirst()
            .orElseThrow(()->new DomainException(HttpStatus.NOT_FOUND,"OUTLET_NOT_FOUND","Outlet not found"));
        requireStaff(brandId,principal,false);
        return brandId;
    }
    public String requireItem(String itemId,Authentication principal) {
        String brandId=jdbc.query("SELECT c.brand_id FROM menu_items i JOIN menu_categories c "
             +"ON c.category_id=i.category_id WHERE i.item_id=?",(rs,n)->rs.getString(1),itemId)
            .stream().findFirst().orElseThrow(()->new DomainException(HttpStatus.NOT_FOUND,
                "ITEM_NOT_FOUND","Item not found"));
        requireStaff(brandId,principal,false);
        return brandId;
    }
}
