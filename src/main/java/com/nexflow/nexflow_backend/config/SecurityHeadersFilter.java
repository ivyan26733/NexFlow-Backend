package com.nexflow.nexflow_backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds security response headers to every HTTP response.
 * Complements Spring Security's built-in header support.
 */
@Component
@Order(1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                     HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options",  "nosniff");
        res.setHeader("X-XSS-Protection",        "1; mode=block");
        res.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=()");
        res.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "connect-src 'self' wss: https:; " +
                "img-src 'self' data: https:; " +
                "frame-ancestors 'none'");
        chain.doFilter(req, res);
    }
}
