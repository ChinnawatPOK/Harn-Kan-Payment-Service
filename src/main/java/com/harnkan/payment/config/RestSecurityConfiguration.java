package com.harnkan.payment.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class RestSecurityConfiguration {
    @Bean
    SecurityFilterChain restSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/webhooks/stripe").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Access denied\",\"timestamp\":\""
                            + java.time.Instant.now() + "\"}");
                }))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setHeader("WWW-Authenticate", "Bearer");
                            response.setContentType("application/json");
                            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Valid JWT required\",\"timestamp\":\""
                                    + java.time.Instant.now() + "\"}");
                        }));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${payment.jwt-secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (secret.length() < 32 || key.length < 32) {
            throw new IllegalArgumentException("payment.jwt-secret must contain at least 32 characters");
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefault();
        OAuth2TokenValidator<Jwt> strictTimestamps = new JwtTimestampValidator(Duration.ZERO);
        decoder.setJwtValidator(jwt -> {
            OAuth2TokenValidatorResult result = defaults.validate(jwt);
            if (result.hasErrors()) return result;
            result = strictTimestamps.validate(jwt);
            if (result.hasErrors()) return result;
            if (jwt.getExpiresAt() == null || !(jwt.getClaims().get("sub") instanceof String)) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "JWT must have exp and a string sub", null));
            }
            return OAuth2TokenValidatorResult.success();
        });
        return decoder;
    }
}
