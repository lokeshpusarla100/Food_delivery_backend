package com.fooddelivery.restaurant.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.restaurant.dto.request.BrandRequestDTO;
import com.fooddelivery.restaurant.dto.response.BrandResponseDTO;
import com.fooddelivery.restaurant.service.BrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/brands")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Brand Management", description = "APIs for managing restaurant brands")
public class BrandController {

        private final BrandService brandService;

        @PostMapping("/register")
        @Operation(summary = "Register a new brand", description = "Creates a new restaurant brand definition")
        @ApiResponses(value = {
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Brand registered successfully"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request data", content = @Content),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Brand already exists", content = @Content)
        })
        public ResponseEntity<ApiResponse<BrandResponseDTO>> registerBrand(
                        @Valid @RequestBody BrandRequestDTO brandRequestDTO) {
                log.info("Registering new brand with name: {}", brandRequestDTO.getName());
                BrandResponseDTO brandResponseDTO = brandService.registerBrand(brandRequestDTO);
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ApiResponse.success(brandResponseDTO, "Brand registered successfully"));
        }

        @GetMapping("/{brandId}")
        @Operation(summary = "Get brand by ID", description = "Retrieves a brand by its unique identifier")
        @ApiResponses(value = {
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Brand found"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Brand not found", content = @Content)
        })
        public ResponseEntity<ApiResponse<BrandResponseDTO>> getBrandById(
                        @Parameter(description = "ID of the brand", required = true, example = "123e4567-e89b-12d3-a456-426614174001") @RequestParam String brandId) {
                log.info("Fetching brand with ID: {}", brandId);
                BrandResponseDTO brandResponseDTO = brandService.getBrandById(brandId);
                return ResponseEntity.ok(ApiResponse.success(brandResponseDTO, "Brand fetched successfully"));
        }
}
