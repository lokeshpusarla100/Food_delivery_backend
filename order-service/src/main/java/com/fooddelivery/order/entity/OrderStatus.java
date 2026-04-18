package com.fooddelivery.order.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_statuses", uniqueConstraints = {
        @UniqueConstraint(name = "uq_order_statuses_code", columnNames = "code")
}, indexes = {
        @Index(name = "idx_order_statuses_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatus implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "status_id")
    private Short statusId;

    @NotBlank
    @Size(max = 30)
    @Column(name = "code", nullable = false, length = 30, unique = true)
    private String code;

    @Size(max = 255)
    @Column(name = "description")
    private String description;

    @NotNull
    @Column(name = "display_order", nullable = false, columnDefinition = "TINYINT")
    @Builder.Default
    private Short displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @NotNull
    @Column(name = "cache_ttl_seconds", nullable = false)
    @Builder.Default
    private Integer cacheTtlSeconds = 3600;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
