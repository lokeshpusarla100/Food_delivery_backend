package com.fooddelivery.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "order_items", uniqueConstraints = {
        @UniqueConstraint(name = "uq_order_item_id", columnNames = "order_item_id")
}, indexes = {
        @Index(name = "idx_order_items_order", columnList = "order_id, line_number"),
        @Index(name = "idx_order_items_prep_status", columnList = "order_id, prep_status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_seq_id")
    private Long itemSeqId;

    @UuidGenerator
    @Column(name = "order_item_id", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String orderItemId;

    @Column(name = "order_id", nullable = false, columnDefinition = "CHAR(36)")
    private String orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_seq_ref", nullable = false, foreignKey = @ForeignKey(name = "fk_items_order"))
    private Order order;

    @Column(name = "line_number", nullable = false)
    @Builder.Default
    private Integer lineNumber = 1;

    @Column(name = "catalog_item_id", columnDefinition = "CHAR(36)")
    private String catalogItemId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "snapshot_base_price", nullable = false, precision = 13, scale = 2)
    private BigDecimal snapshotBasePrice;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "items_line_total", nullable = false, precision = 13, scale = 2)
    private BigDecimal itemsLineTotal;

    @Column(name = "instructions")
    private String instructions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "json")
    private String metadata;

    @Column(name = "prep_status", nullable = false, columnDefinition = "VARCHAR(20)")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PrepStatus prepStatus = PrepStatus.NOT_STARTED;

    @Column(name = "kds_ticket_id", length = 50)
    private String kdsTicketId;

    @Column(name = "started_preparing_at")
    private LocalDateTime startedPreparingAt;

    @Column(name = "finished_preparing_at")
    private LocalDateTime finishedPreparingAt;

    @Column(name = "is_completed", nullable = false)
    @Builder.Default
    private Boolean isCompleted = false;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItemModifier> modifiers = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (order != null && orderId == null) {
            orderId = order.getOrderId();
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void addModifier(OrderItemModifier modifier) {
        modifiers.add(modifier);
        modifier.setOrderItem(this);
    }

    public void removeModifier(OrderItemModifier modifier) {
        modifiers.remove(modifier);
        modifier.setOrderItem(null);
    }

    public enum PrepStatus {
        NOT_STARTED, PREPARING, READY, CANCELLED
    }
}
