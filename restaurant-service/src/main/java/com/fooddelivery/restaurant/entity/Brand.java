package com.fooddelivery.restaurant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

import java.io.Serializable;

@Entity
@Table(name = "brands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Brand implements Serializable {

    @Id
    @UuidGenerator
    @GeneratedValue
    @Column(name = "brand_id", updatable = false, nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String brandId;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "logo_url", length = 255)
    private String logoUrl;

    @Column(name = "corporate_phone", length = 20)
    private String corporatePhone;

    @Column(name = "cuisine_type", length = 50)
    private String cuisineType;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
