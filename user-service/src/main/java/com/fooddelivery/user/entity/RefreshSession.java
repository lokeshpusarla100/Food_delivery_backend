package com.fooddelivery.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name="refresh_tokens")
@Getter @Setter
public class RefreshSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="token_id")
    private Long tokenId;
    @Column(name="user_id", nullable=false, length=36)
    private String userId;
    @Column(name="device_id", length=255)
    private String deviceId;
    @Column(name="token_hash", nullable=false, length=255, unique=true)
    private String tokenHash;
    @Column(name="expires_at", nullable=false)
    private LocalDateTime expiresAt;
    @Column(name="revoked", nullable=false)
    private boolean revoked;
    @Column(name="created_at", nullable=false)
    private LocalDateTime createdAt;
}
