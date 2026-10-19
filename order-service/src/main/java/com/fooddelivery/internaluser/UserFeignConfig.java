package com.fooddelivery.internaluser;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import java.nio.charset.StandardCharsets;

// Deliberately outside com.fooddelivery.order component scan:
// interceptor MUST NOT attach the internal key to restaurant Feign requests.
public class UserFeignConfig {
    @Bean
    public RequestInterceptor userServiceAuth(@Value("${USER_INTERNAL_API_KEY:}") String key) {
        if (key.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Order Service requires USER_INTERNAL_API_KEY (>=32 UTF-8 bytes)");
        }
        return template -> template.header("X-Internal-Api-Key",key);
    }
}
