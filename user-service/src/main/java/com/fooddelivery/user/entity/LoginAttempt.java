package com.fooddelivery.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name="login_attempts")
@Getter @Setter
public class LoginAttempt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="attempt_id")
    private Long id;
    @Column(name="identifier", nullable=false)
    private String identifier;
    @Column(name="identifier_type",nullable=false)
    private String identifierType = "EMAIL";
    @Column(name="attempt_status",nullable=false)
    private String attemptStatus;
    @Column(name="failure_reason")
    private String failureReason;
    @Column(name="ip_address")
    private String ipAddress;
    @Column(name="attempted_at")
    private LocalDateTime attemptedAt;
}
