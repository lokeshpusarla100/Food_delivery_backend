package com.fooddelivery.user.service;

import com.fooddelivery.user.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class EmailSender {
    private final JavaMailSender mail;
    @Value("${security.auth.email-from:}") private String sender;
    @Value("${security.auth.public-base-url:}") private String baseUrl;

    public void sendToken(String email, String purpose, String token) {
        if(sender.isBlank() || baseUrl.isBlank())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"EMAIL_NOT_CONFIGURED","Email delivery is not configured");
        String action = purpose.equals("verify-email") ? "verify-email" : "reset-password";
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(sender);
        message.setTo(email);
        message.setSubject("Food Delivery - " + (action.equals("verify-email") ? "Verify your email" : "Reset your password"));
        message.setText("Open this link to continue: " + baseUrl + "/" + action + "?token="
            + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)
            + "\n\nIf you did not request this message, ignore it.");
        try {
            mail.send(message);
        } catch (MailException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"EMAIL_UNAVAILABLE","Email delivery is temporarily unavailable");
        }
    }
}
