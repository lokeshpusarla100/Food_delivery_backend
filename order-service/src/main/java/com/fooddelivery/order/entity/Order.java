package com.fooddelivery.order.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders", uniqueConstraints = {
                @UniqueConstraint(name = "uq_order_id", columnNames = "order_id"),
                @UniqueConstraint(name = "uq_order_number", columnNames = "order_number"),
                @UniqueConstraint(name = "uq_orders_user_idempotency", columnNames = { "user_id", "idempotency_key" })
}, indexes = {
                @Index(name = "idx_orders_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_orders_outlet_status", columnList = "outlet_id, current_status_id, created_at"),
                @Index(name = "idx_orders_payment_status", columnList = "payment_status, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order implements Serializable {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "order_seq_id")
        private Long orderSeqId;

        @Column(name = "order_id", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
        @Builder.Default
        private String orderId = UUID.randomUUID().toString();

        @Size(max = 64)
        @Column(name = "order_number", nullable = false, length = 64, unique = true)
        private String orderNumber;

        @Size(max = 128)
        @Column(name = "idempotency_key", length = 128)
        private String idempotencyKey;

        // User and Outlet Information
        @Column(name = "user_id", nullable = false, columnDefinition = "CHAR(36)")
        private String userId;

        @Column(name = "outlet_id", nullable = false, columnDefinition = "CHAR(36)")
        private String outletId;

        @Column(name = "outlet_name_at_order", nullable = false)
        private String outletNameAtOrder;

        @Column(name = "outlet_city_id", nullable = false, columnDefinition = "CHAR(36)")
        private String outletCityId;

        // Delivery Information
        @Column(name = "delivery_partner_id", columnDefinition = "CHAR(36)")
        private String deliveryPartnerId;

        @Column(name = "driver_assigned_at")
        private LocalDateTime driverAssignedAt;

        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "delivery_address_snapshot", nullable = false, columnDefinition = "json")
        private DeliveryAddressSnapshot deliveryAddressSnapshot;

        // Payment Information
        @Size(max = 100)
        @Column(name = "payment_transaction_id", length = 100)
        private String paymentTransactionId;

        @Column(name = "payment_status", nullable = false, columnDefinition = "VARCHAR(30)")
        @Enumerated(EnumType.STRING)
        @Builder.Default
        private PaymentStatus paymentStatus = PaymentStatus.PENDING;

        // Order Status
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "current_status_id", nullable = false, foreignKey = @ForeignKey(name = "fk_orders_status"))
        private OrderStatus currentStatus;

        @Column(name = "order_type", nullable = false, columnDefinition = "VARCHAR(20)")
        @Enumerated(EnumType.STRING)
        @Builder.Default
        private OrderType orderType = OrderType.ASAP;

        @Column(name = "scheduled_for")
        private LocalDateTime scheduledFor;

        @Column(name = "instructions", columnDefinition = "TEXT")
        private String instructions;

        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "metadata", columnDefinition = "json")
        private String metadata;

        // Pricing Information
        @Column(name = "items_total", nullable = false, precision = 13, scale = 2)
        private BigDecimal itemsTotal;

        @Column(name = "adjustments_total", nullable = false, precision = 13, scale = 2)
        @Builder.Default
        private BigDecimal adjustmentsTotal = BigDecimal.ZERO;

        @Column(name = "subtotal", nullable = false, precision = 13, scale = 2)
        private BigDecimal subtotal;

        @Column(name = "tax", nullable = false, precision = 13, scale = 2)
        @Builder.Default
        private BigDecimal tax = BigDecimal.ZERO;

        @Column(name = "delivery_fee", nullable = false, precision = 13, scale = 2)
        @Builder.Default
        private BigDecimal deliveryFee = BigDecimal.ZERO;

        @Column(name = "promo_code_applied", length = 50)
        private String promoCodeApplied;

        @Column(name = "discount_amount", nullable = false, precision = 13, scale = 2)
        @Builder.Default
        private BigDecimal discountAmount = BigDecimal.ZERO;

        @Column(name = "total_amount", nullable = false, precision = 13, scale = 2)
        private BigDecimal totalAmount;

        @Column(name = "currency_code", nullable = false, length = 3, columnDefinition = "CHAR(3)")
        @Builder.Default
        private String currencyCode = "INR";

        @Column(name = "partner_earning_amount", precision = 13, scale = 2)
        private BigDecimal partnerEarningAmount;

        // Delivery Tracking
        @Column(name = "promised_delivery_time")
        private LocalDateTime promisedDeliveryTime;

        @Column(name = "actual_delivery_time")
        private LocalDateTime actualDeliveryTime;

        @Column(name = "sla_breached", nullable = false)
        @Builder.Default
        private boolean slaBreached = false;

        // Cancellation Information
        @Column(name = "is_cancelled", nullable = false)
        @Builder.Default
        private boolean isCancelled = false;

        @Column(name = "cancelled_at")
        private LocalDateTime cancelledAt;

        @Column(name = "cancellation_reason")
        private String cancellationReason;

        // Feedback
        @Column(name = "user_rating")
        private Short userRating;

        @Column(name = "user_feedback", columnDefinition = "TEXT")
        private String userFeedback;

        @Column(name = "rated_at")
        private LocalDateTime ratedAt;

        // Error Handling
        @Column(name = "retry_count", nullable = false)
        @Builder.Default
        private Short retryCount = 0;

        @Column(name = "last_error_message", length = 500)
        private String lastErrorMessage;

        // Optimistic Locking
        @Version
        @Column(name = "version", nullable = false)
        @Builder.Default
        private Integer version = 1;

        @Column(name = "checksum", columnDefinition = "CHAR(64)")
        private String checksum;

        // Audit Fields
        @CreationTimestamp
        @Column(name = "created_at", nullable = false, updatable = false)
        private LocalDateTime createdAt;

        @UpdateTimestamp
        @Column(name = "updated_at", nullable = false)
        private LocalDateTime updatedAt;

        // Relationships
        @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
        @Builder.Default
        private List<OrderItem> items = new ArrayList<>();

        // Helper Methods
        public void addItem(OrderItem item) {
                items.add(item);
                item.setOrder(this);
        }

        public void removeItem(OrderItem item) {
                items.remove(item);
                item.setOrder(null);
        }

        // Domain Helpers
        public boolean isActive() {
                return !isCancelled;
        }

        @PrePersist
        @PreUpdate
        private void validateAmounts() {
                if (totalAmount != null && totalAmount.compareTo(BigDecimal.ZERO) < 0) {
                        throw new IllegalStateException("Total amount cannot be negative");
                }
        }

        @Override
        public boolean equals(Object o) {
                if (this == o)
                        return true;
                if (!(o instanceof Order))
                        return false;
                return orderId != null && orderId.equals(((Order) o).getOrderId());
        }

        @Override
        public int hashCode() {
                return orderId != null ? orderId.hashCode() : 0;
        }

        // Enums
        public enum PaymentStatus {
                PENDING, CAPTURED, FAILED, REFUNDED
        }

        public enum OrderType {
                ASAP,
                SCHEDULED
        }
}
