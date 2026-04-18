package com.fooddelivery.restaurant.repository;

import com.fooddelivery.restaurant.entity.Outlet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutletRepository extends JpaRepository<Outlet, String> {
    List<Outlet> findAllByBrand_BrandId(String brandId);
}
