package com.fooddelivery.order.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "order_item_modifiers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_modifier_id", columnNames = "modifier_detail_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemModifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "modifier_seq_id")
    private Long modifierSeqId;

    @Column(name = "modifier_detail_id", nullable = false, updatable = false, columnDefinition = "CHAR(36)")
    private String modifierDetailId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_seq_ref", nullable = false,
            foreignKey = @ForeignKey(name = "fk_modifiers_item"))
    private OrderItem orderItem;

    @Column(name = "catalog_modifier_id", columnDefinition = "CHAR(36)")
    private String catalogModifierId;

    @Column(name = "modifier_name", nullable = false)
    private String modifierName;

    @Column(name = "snapshot_price_adjustment", nullable = false, precision = 13, scale = 2)
    private BigDecimal snapshotPriceAdjustment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (modifierDetailId == null) {
            modifierDetailId = UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
    }
}
