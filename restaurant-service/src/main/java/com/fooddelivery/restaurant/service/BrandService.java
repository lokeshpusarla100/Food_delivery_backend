package com.fooddelivery.restaurant.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fooddelivery.restaurant.dto.request.BrandRequestDTO;
import com.fooddelivery.restaurant.dto.response.BrandResponseDTO;
import com.fooddelivery.restaurant.entity.Brand;
import com.fooddelivery.restaurant.repository.BrandRepository;

import com.fooddelivery.restaurant.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrandService {

    private final BrandRepository brandRepository;

    @Transactional
    public BrandResponseDTO registerBrand(BrandRequestDTO brandRequestDTO) {
        log.debug("Registering new brand with name: {}", brandRequestDTO.getName());

        // 1. Check if brand already exists
        if (brandRepository.existsByName(brandRequestDTO.getName())) {
            throw new RuntimeException("Brand already exists");
        }

        // 2. Build Brand entity from request
        Brand brand = Brand.builder()
                .name(brandRequestDTO.getName())
                .logoUrl(brandRequestDTO.getLogoUrl())
                .corporatePhone(brandRequestDTO.getCorporatePhone())
                .cuisineType(brandRequestDTO.getCuisineType())
                .build();

        // 3. Save to database
        Brand savedBrand = brandRepository.save(brand);
        log.info("Successfully registered brand with ID: {}", savedBrand.getBrandId());

        // 4. Return response DTO
        return mapToResponse(savedBrand);
    }

    private BrandResponseDTO mapToResponse(Brand brand) {
        return BrandResponseDTO.builder()
                .brandId(brand.getBrandId())
                .name(brand.getName())
                .logoUrl(brand.getLogoUrl())
                .corporatePhone(brand.getCorporatePhone())
                .cuisineType(brand.getCuisineType())
                .build();
    }

    @Transactional(readOnly = true)
    public BrandResponseDTO getBrandById(String brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new ResourceNotFoundException("brand not found with Id " + brandId));
        return mapToResponse(brand);
    }

}
