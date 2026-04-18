package com.fooddelivery.restaurant.controller;

import com.fooddelivery.commons.dto.ApiResponse;
import com.fooddelivery.restaurant.dto.request.OutletRequestDTO;
import com.fooddelivery.restaurant.dto.response.OutletResponseDTO;
import com.fooddelivery.restaurant.service.OutletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequestMapping("/api/v1/outlets")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Outlet Management", description = "APIs for managing restaurant outlets")
public class OutletController {

        private final OutletService outletService;

        @PostMapping
        @Operation(summary = "Register a new outlet", description = "Creates a new outlet for a brand")
        @ApiResponses(value = {
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Outlet registered successfully"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request data", content = @Content),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Brand not found", content = @Content)
        })
        public ResponseEntity<ApiResponse<OutletResponseDTO>> registerOutlet(
                        @Valid @RequestBody OutletRequestDTO outletRequestDTO) {
                log.info("Request to register outlet for brand: {}", outletRequestDTO.getBrandId());
                OutletResponseDTO responseDTO = outletService.registerOutlet(outletRequestDTO);
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ApiResponse.success(responseDTO, "Outlet registered successfully"));
        }

        @GetMapping
        @Operation(summary = "Get outlet by ID", description = "Retrieves an outlet by its unique identifier")
        @ApiResponses(value = {
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Outlet found"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Outlet not found", content = @Content)
        })
        public ResponseEntity<ApiResponse<OutletResponseDTO>> getOutlet(
                        @Parameter(description = "ID of the outlet", required = true, example = "123e4567-e89b-12d3-a456-426614174002") @RequestParam String outletId) {
                log.info("Fetching outlet with ID: {}", outletId);
                OutletResponseDTO responseDTO = outletService.getOutlet(outletId);
                return ResponseEntity.ok(ApiResponse.success(responseDTO, "Outlet fetched successfully"));
        }

        @GetMapping("/validate")
        @Operation(summary = "Validate outlet exists", description = "Checks if an outlet exists by ID")
        @ApiResponses(value = {
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Validation check completed")
        })
        public ResponseEntity<ApiResponse<Boolean>> validateOutlet(
                        @Parameter(description = "ID of the outlet to validate", required = true, example = "123e4567-e89b-12d3-a456-426614174002") @RequestParam String outletId) {
                Boolean exists = outletService.validateOutlet(outletId);
                return ResponseEntity
                                .ok(ApiResponse.success(exists, exists ? "Outlet exists" : "Outlet does not exist"));
        }
}
