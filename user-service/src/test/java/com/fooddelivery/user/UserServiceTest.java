package com.fooddelivery.user;

import com.fooddelivery.user.dto.*;
import com.fooddelivery.user.entity.User;
import com.fooddelivery.user.exception.ApiException;
import com.fooddelivery.user.repository.*;
import com.fooddelivery.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock UserRepository users;
    @Mock RefreshSessionRepository sessions;
    @Mock PasswordEncoder passwords;
    @InjectMocks UserService service;

    @Test void registrationNormalizesEmailAndHashesPassword() {
        var req=new UserRegistrationRequest("  TEST@EXAMPLE.COM  ","+919876543210",
            "very-secure-password"," Example User ");
        when(passwords.encode("very-secure-password")).thenReturn("hashed");
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User user=inv.getArgument(0);
            user.setUserId("a123");return user;
        });
        UserResponse response=service.registerUser(req);
        assertEquals("test@example.com",response.getEmail());
        assertEquals("Example User",response.getFullName());
        ArgumentCaptor<User> captured=ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captured.capture());
        assertEquals("hashed",captured.getValue().getPasswordHash());
        assertFalse(captured.getValue().isVerified());
    }
    @Test void cannotRegisterExistingEmail() {
        when(users.existsByEmail("test@example.com")).thenReturn(true);
        var req=new UserRegistrationRequest("test@example.com","+919876543210",
            "very-secure-password","Some User");
        ApiException ex=assertThrows(ApiException.class,()->service.registerUser(req));
        assertEquals("USER_EXISTS",ex.code());
        verify(users,never()).saveAndFlush(any());
    }
    @Test void deactivationRevokesAllSessions() {
        User user=User.builder().userId("user-1").isActive(true).passwordHash("hash").build();
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        when(passwords.matches("password","hash")).thenReturn(true);
        service.deactivate("user-1","password");
        assertFalse(user.isActive());
        assertEquals(1L,user.getTokenVersion());
        verify(sessions).revokeAll("user-1");
    }
}
