package com.fooddelivery.user.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.user.dto.AddressDtos.*;
import com.fooddelivery.user.service.AddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/addresses")
public class AddressController {
    private final AddressService addresses;
    @GetMapping
    public ApiResponse<List<AddressResponse>> list(Authentication p,
          @RequestParam(name="limit",defaultValue="30") int limit,
          @RequestParam(name="offset",defaultValue="0") int offset) {
        if(limit<1 || limit>100 || offset<0)
            throw new com.fooddelivery.user.exception.ApiException(
                HttpStatus.BAD_REQUEST,"BAD_PAGINATION","Invalid pagination");
        return ApiResponse.success(addresses.list(p.getName(),limit,offset),"Addresses");
    }
    @PostMapping
    public ResponseEntity<ApiResponse<AddressResponse>> add(Authentication p,
           @Valid @RequestBody AddressRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(addresses.add(p.getName(),req),"Address created"));
    }
    @PutMapping("/{id}")
    public ApiResponse<AddressResponse> update(Authentication p,@PathVariable("id") long id,
           @Valid @RequestBody AddressRequest req) {
        return ApiResponse.success(addresses.update(p.getName(),id,req),"Address updated");
    }
    @PutMapping("/{id}/default")
    public ApiResponse<Void> setDefault(Authentication p,@PathVariable("id") long id) {
        addresses.setDefault(p.getName(),id);
        return ApiResponse.success(null,"Default address updated");
    }
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(Authentication p,@PathVariable("id") long id) {
        addresses.delete(p.getName(),id);
        return ApiResponse.success(null,"Address deleted");
    }
}
