package com.fooddelivery.restaurant.service;

import com.fooddelivery.restaurant.dto.request.OutletRequestDTO;
import com.fooddelivery.restaurant.dto.response.OutletResponseDTO;
import com.fooddelivery.restaurant.entity.Brand;
import com.fooddelivery.restaurant.entity.Outlet;
import com.fooddelivery.restaurant.repository.BrandRepository;
import com.fooddelivery.restaurant.repository.OutletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fooddelivery.restaurant.exception.ResourceNotFoundException;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutletService {

    private final OutletRepository outletRepository;
    private final BrandRepository brandRepository;

    @Transactional
    public OutletResponseDTO registerOutlet(OutletRequestDTO outletRequestDTO) {
        log.info("Registering new outlet for brand ID: {}", outletRequestDTO.getBrandId());

        Brand brand = brandRepository.findById(outletRequestDTO.getBrandId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Brand not found with ID: " + outletRequestDTO.getBrandId()));

        Outlet outlet = Outlet.builder()
                .brand(brand)
                .street(outletRequestDTO.getStreet())
                .locality(outletRequestDTO.getLocality())
                .city(outletRequestDTO.getCity())
                .state(outletRequestDTO.getState())
                .postalCode(outletRequestDTO.getPostalCode())
                .country(outletRequestDTO.getCountry())
                .locationName(outletRequestDTO.getLocationName())
                .latitude(outletRequestDTO.getLatitude())
                .longitude(outletRequestDTO.getLongitude())
                .isActive(true)
                .build();

        Outlet savedOutlet = outletRepository.save(outlet);
        log.info("Successfully registered outlet with ID: {}", savedOutlet.getOutletId());

        return mapToResponse(savedOutlet);
    }

    @Transactional(readOnly = true)
    public OutletResponseDTO getOutlet(String outletId) {
        Outlet outlet = outletRepository.findById(outletId)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet not found with ID: " + outletId));
        return mapToResponse(outlet);
    }

    @Transactional(readOnly = true)
    public Boolean validateOutlet(String outletId) {
        return outletRepository.existsById(outletId);
    }

    private OutletResponseDTO mapToResponse(Outlet outlet) {
        return OutletResponseDTO.builder()
                .outletId(outlet.getOutletId())
                .brandId(outlet.getBrand().getBrandId())
                .street(outlet.getStreet())
                .locality(outlet.getLocality())
                .city(outlet.getCity())
                .state(outlet.getState())
                .postalCode(outlet.getPostalCode())
                .country(outlet.getCountry())
                .locationName(outlet.getLocationName())
                .latitude(outlet.getLatitude())
                .longitude(outlet.getLongitude())
                .isActive(outlet.getIsActive())
                .createdAt(outlet.getCreatedAt())
                .build();
    }
}
