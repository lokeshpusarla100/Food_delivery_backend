package com.fooddelivery.user.service;

import com.fooddelivery.user.dto.*;
import com.fooddelivery.user.dto.AuthDtos.ProfileUpdateRequest;
import com.fooddelivery.user.entity.User;
import com.fooddelivery.user.exception.ApiException;
import com.fooddelivery.user.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service @RequiredArgsConstructor
public class UserService {
    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final PasswordEncoder passwords;

    public static String normalizedEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
    public static void validatePassword(String raw) {
        if (raw == null || raw.length() < 12 || raw.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PASSWORD",
                "Password must be at least 12 characters and at most 72 UTF-8 bytes");
        }
    }
    @Transactional
    public UserResponse registerUser(UserRegistrationRequest request) {
        validatePassword(request.getPassword());
        String email = normalizedEmail(request.getEmail());
        if (users.existsByEmail(email) || users.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new ApiException(HttpStatus.CONFLICT,"USER_EXISTS","An account with these details already exists");
        }
        User user = User.builder()
            .email(email)
            .phoneNumber(request.getPhoneNumber())
            .fullName(request.getFullName().trim())
            .passwordHash(passwords.encode(request.getPassword()))
            .isActive(true).isVerified(false).build();
        return toResponse(users.saveAndFlush(user));
    }
    @Transactional(readOnly=true)
    public UserResponse getUserById(String userId) {
        return toResponse(requireUser(userId));
    }
    @Transactional(readOnly=true)
    public UserResponse getUserByEmail(String email) {
        User user=users.findByEmail(normalizedEmail(email))
            .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","User not found"));
        return toResponse(user);
    }
    @Transactional
    public UserResponse updateMyProfile(String id, ProfileUpdateRequest request) {
        User user = requireActiveUser(id);
        if(request.fullName()!=null) {
            if(request.fullName().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_NAME","Full name is required");
            user.setFullName(request.fullName().trim());
        }
        if(request.phoneNumber()!=null) {
            if(users.existsByPhoneNumberAndUserIdNot(request.phoneNumber(), id))
                throw new ApiException(HttpStatus.CONFLICT,"PHONE_EXISTS","Phone number already in use");
            user.setPhoneNumber(request.phoneNumber());
        }
        return toResponse(users.saveAndFlush(user));
    }
    @Transactional
    public void deactivate(String id, String password) {
        User user = requireActiveUser(id);
        if(!passwords.matches(password, user.getPasswordHash()))
            throw new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Invalid credentials");
        user.setActive(false);
        user.setTokenVersion(user.getTokenVersion()+1);
        sessions.revokeAll(id);
        users.save(user);
    }
    public User requireUser(String id) {
        return users.findById(id)
            .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","User not found"));
    }
    public User requireActiveUser(String id) {
        User user = requireUser(id);
        if(!user.isActive()) throw new ApiException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","Account is disabled");
        return user;
    }
    public static UserResponse toResponse(User user) {
        return UserResponse.builder().userId(user.getUserId()).email(user.getEmail())
            .phoneNumber(user.getPhoneNumber()).fullName(user.getFullName())
            .isVerified(user.isVerified()).isActive(user.isActive())
            .createdAt(user.getCreatedAt()).build();
    }
}
