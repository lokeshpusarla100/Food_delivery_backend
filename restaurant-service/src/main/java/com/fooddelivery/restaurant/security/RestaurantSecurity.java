package com.fooddelivery.restaurant.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration
public class RestaurantSecurity {
    @Bean
    SecretKeySpec tokenKey(@Value("${security.jwt.secret}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("USER_JWT_SECRET (32+ bytes) is required for Restaurant Service");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256");
    }
    @Bean
    JwtDecoder decoder(SecretKeySpec tokenKey,@Value("${security.jwt.issuer}") String issuer,
                       @Value("${security.jwt.audience}") String audience) {
        NimbusJwtDecoder jwt=NimbusJwtDecoder.withSecretKey(tokenKey).macAlgorithm(MacAlgorithm.HS256).build();
        jwt.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                a -> a != null && a.contains(audience))));
        return jwt;
    }
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, @Value("${security.restaurant.platform-key:}") String platform,
                                      @Value("${security.restaurant.internal-key:}") String internal) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health","/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/brands/**","/api/v1/outlets/**","/api/v1/menus/**").permitAll()
                .requestMatchers("/api/v1/platform/**").hasRole("PLATFORM")
                .requestMatchers("/api/v1/internal/**").hasRole("SERVICE")
                .requestMatchers("/api/v1/manage/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(org.springframework.security.config.Customizer.withDefaults()));
        http.addFilterBefore(new MachineKeyFilter(platform,internal),BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    private static final class MachineKeyFilter extends OncePerRequestFilter {
        private final byte[] platform, internal;
        MachineKeyFilter(String platform,String internal) {
            this.platform=platform.getBytes(StandardCharsets.UTF_8);
            this.internal=internal.getBytes(StandardCharsets.UTF_8);
        }
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse resp,FilterChain chain)
            throws java.io.IOException,ServletException {
            boolean isPlatform=req.getRequestURI().startsWith("/api/v1/platform/");
            boolean isInternal=req.getRequestURI().startsWith("/api/v1/internal/");
            if (isPlatform || isInternal) {
                byte[] expected=isPlatform?platform:internal;
                String supplied=req.getHeader(isPlatform?"X-Restaurant-Platform-Key":"X-Restaurant-Internal-Key");
                if(expected.length >= 32 && supplied!=null
                   && MessageDigest.isEqual(expected,supplied.getBytes(StandardCharsets.UTF_8))) {
                    SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(isPlatform?"platform":"service",null,
                            List.of(new SimpleGrantedAuthority(isPlatform?"ROLE_PLATFORM":"ROLE_SERVICE"))));
                }
            }
            chain.doFilter(req,resp);
        }
    }
}
