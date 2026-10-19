package com.fooddelivery.user.config;

import com.fooddelivery.user.entity.User;
import com.fooddelivery.user.repository.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);
    }
    @Bean public SecretKeySpec signingKey(@Value("${security.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("USER_JWT_SECRET must be at least 32 UTF-8 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
    @Bean JwtEncoder jwtEncoder(SecretKeySpec signingKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
    }
    @Bean JwtDecoder jwtDecoder(SecretKeySpec signingKey,
          @Value("${security.jwt.issuer}") String issuer,
          @Value("${security.jwt.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey)
            .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, audiences ->
                audiences != null && audiences.contains(audience))));
        return decoder;
    }
    public Converter<Jwt, JwtAuthenticationToken> jwtAuthenticationConverter(UserRepository users) {
        return jwt -> {
            User user = users.findById(jwt.getSubject())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
            Object ver = jwt.getClaim("ver");
            if (!user.isActive() || !(ver instanceof Number)
                    || user.getTokenVersion() != ((Number) ver).longValue()) {
                throw new BadCredentialsException("Invalid credentials");
            }
            return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_USER")), user.getUserId());
        };
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, UserRepository users,
            @Value("${security.internal.api-key:}") String internalKey) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                    "/api/v1/auth/refresh", "/api/v1/auth/verify-email",
                    "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/internal/**").hasRole("SERVICE")
                .requestMatchers(HttpMethod.GET, "/api/v1/users/get-by-id", "/api/v1/users/get-by-email").hasRole("SERVICE")
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter(users))));
        http.addFilterBefore(new InternalKeyFilter(internalKey), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static final class InternalKeyFilter extends OncePerRequestFilter {
        private final byte[] expected;
        InternalKeyFilter(String key) { this.expected = key.getBytes(StandardCharsets.UTF_8); }
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
                throws java.io.IOException, ServletException {
            String supplied = req.getHeader("X-Internal-Api-Key");
            if (expected.length >= 32 && supplied != null
                  && MessageDigest.isEqual(expected,supplied.getBytes(StandardCharsets.UTF_8))) {
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken("order-service", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
            }
            chain.doFilter(req,res);
        }
    }
}
