package com.fooddelivery.user.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.user.dto.PreferencesDtos.*;
import com.fooddelivery.user.service.PreferencesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/preferences")
public class PreferencesController {
    private final PreferencesService service;
    @GetMapping
    public ApiResponse<PreferencesResponse> get(Authentication p) {
        return ApiResponse.success(service.get(p.getName()),"Preferences");
    }
    @PutMapping
    public ApiResponse<PreferencesResponse> put(Authentication p,@Valid @RequestBody PreferencesRequest req) {
        return ApiResponse.success(service.put(p.getName(),req),"Preferences saved");
    }
}
