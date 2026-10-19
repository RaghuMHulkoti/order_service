package com.learning.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets a browser page on another origin call this API. Off unless {@code cors.allowed-origins} lists origins
 * (comma separated), so a deployment that sets nothing accepts no cross-origin browser calls.
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(@Value("${cors.allowed-origins:}") String[] allowedOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (allowedOrigins.length == 0 || allowedOrigins[0].isBlank()) {
                    return;
                }
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigins)
                        .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("X-User-Id", "Idempotency-Key", "Content-Type")
                        .maxAge(3600);
            }
        };
    }
}
