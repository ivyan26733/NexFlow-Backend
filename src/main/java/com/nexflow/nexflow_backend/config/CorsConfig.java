// package com.nexflow.nexflow_backend.config;

// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.web.cors.CorsConfiguration;
// import org.springframework.web.cors.CorsConfigurationSource;
// import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// import java.util.Arrays;
// import java.util.List;

// @Configuration
// public class CorsConfig {

//     @Value("${app.cors.allowed-origins:http://localhost:3000}")
//     private String allowedOrigins;

//     @Bean
//     public CorsConfigurationSource corsConfigurationSource() {
//     CorsConfiguration config = new CorsConfiguration();

//     config.setAllowedOriginPatterns(List.of(
//             "https://*.vercel.app",
//             "https://*.ngrok-free.dev"
//     ));

//     config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
//     config.setAllowedHeaders(Arrays.asList("*"));
//     config.setAllowCredentials(true);

//     UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
//     source.registerCorsConfiguration("/**", config);

//     return source;
// }
// }
