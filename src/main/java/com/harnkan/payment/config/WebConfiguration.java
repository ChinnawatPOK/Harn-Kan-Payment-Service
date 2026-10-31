package com.harnkan.payment.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Browser CORS configuration only; Payment has no Spring Security or JWT filter. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String[] origins;

    public WebConfiguration(@Value("${payment.cors.origins:}") String origins) {
        this.origins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length == 0) return;
        registry.addMapping("/**").allowedOrigins(origins).allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("X-User-Id", "Authorization", "Content-Type", "Accept").maxAge(3600);
    }
}
