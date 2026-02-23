package com.nexflow.nexflow_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Local dev
        config.addAllowedOriginPattern("http://localhost:*");
        config.addAllowedOriginPattern("http://127.0.0.1:*");
        // Netlify (production)
        config.addAllowedOriginPattern("https://*.netlify.app");
        config.addAllowedOriginPattern("https://*.netlify.com");

        config.addAllowedMethod("*");   // GET, POST, PUT, DELETE, OPTIONS
        config.addAllowedHeader("*");   // all headers
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config); // apply to all endpoints

        return new CorsFilter(source);
    }
}
