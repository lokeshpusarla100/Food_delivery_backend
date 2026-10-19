package com.fooddelivery.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name="password_reset_tokens")
@Getter @Setter
public class PasswordResetToken {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="token_id")
    private Long id;
    @Column(name="user_id",nullable=false,length=36)
    private String userId;
    @Column(name="token_hash",nullable=false)
    private String tokenHash;
    @Column(name="expires_at",nullable=false)
    private LocalDateTime expiresAt;
    @Column(name="consumed",nullable=false)
    private boolean consumed;
    @Column(name="created_at",nullable=false)
    private LocalDateTime createdAt;
}
