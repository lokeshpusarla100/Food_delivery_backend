package com.fooddelivery.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id @GeneratedValue @UuidGenerator
    @Column(name="user_id", length=36, nullable=false, updatable=false, columnDefinition="CHAR(36)")
    private String userId;
    @Column(nullable=false, unique=true, length=255)
    private String email;
    @Column(name="phone_number", nullable=false, unique=true, length=20)
    private String phoneNumber;
    @Column(name="password_hash", nullable=false, length=255)
    private String passwordHash;
    @Column(name="full_name", length=100)
    private String fullName;
    @Builder.Default @Column(name="is_verified",nullable=false)
    private boolean isVerified = false;
    @Builder.Default @Column(name="is_active",nullable=false)
    private boolean isActive = true;
    @Builder.Default @Column(name="token_version",nullable=false)
    private long tokenVersion = 0;
    @CreationTimestamp @Column(name="created_at",updatable=false)
    private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name="updated_at")
    private LocalDateTime updatedAt;
}
