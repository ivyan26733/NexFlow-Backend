package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.dto.auth.*;
import com.nexflow.nexflow_backend.security.JwtTokenProvider;
import com.nexflow.nexflow_backend.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService      userService;
    private final JwtTokenProvider jwtProvider;

    @Value("${app.local-dev:false}")
    private boolean localDev;

    // ── Public endpoints ──────────────────────────────────────────────────────

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest req) {
        try {
            userService.register(req.email(), req.password(), req.name());
            return ResponseEntity.ok(Map.of(
                    "message", "Verification code sent to " + req.email()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestBody OtpVerifyRequest req,
                                        HttpServletResponse response) {
        try {
            NexUser user  = userService.verifyOtp(req.email(), req.otp());
            String  token = jwtProvider.generateToken(user);
            issueTokenCookie(response, token);
            return ResponseEntity.ok(toAuthResponse(token, user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<?> resendOtp(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        try {
            userService.resendOtp(email);
            return ResponseEntity.ok(Map.of("message", "New verification code sent"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req,
                                    HttpServletResponse response) {
        try {
            NexUser user  = userService.login(req.email(), req.password());
            String  token = jwtProvider.generateToken(user);
            issueTokenCookie(response, token);
            return ResponseEntity.ok(toAuthResponse(token, user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body) {
        try {
            userService.requestPasswordReset(body.get("email"));
        } catch (Exception ignored) {
            // Swallow all errors — never reveal whether an account exists
        }
        return ResponseEntity.ok(Map.of(
                "message", "If an account exists for that email, a reset code has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String email       = body.get("email");
        String otp         = body.get("otp");
        String newPassword = body.get("newPassword");
        try {
            userService.resetPassword(email, otp, newPassword);
            return ResponseEntity.ok(Map.of("message", "Password updated successfully. You can now sign in."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Logout — clears the HttpOnly cookie server-side. */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        ResponseCookie expired = ResponseCookie.from("nexflow_token", "")
                .httpOnly(true)
                .secure(!localDev)
                .sameSite(localDev ? "Lax" : "None")
                .path("/")
                .maxAge(0)    // expire immediately
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    // ── Protected endpoints ───────────────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal NexUser user) {
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(toAuthResponse(null, user));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Issues an HttpOnly cookie on production.
     * On local dev the cookie is skipped — the token is returned in the response body
     * and the frontend stores it in localStorage as before.
     */
    private void issueTokenCookie(HttpServletResponse response, String token) {
        if (localDev) return;   // local: token goes in response body, no cookie needed
        ResponseCookie cookie = ResponseCookie.from("nexflow_token", token)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")    // cross-domain: Vercel frontend ↔ EC2 backend
                .path("/")
                .maxAge(Duration.ofDays(7))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private AuthResponse toAuthResponse(String token, NexUser user) {
        // On prod: don't include token in body (it's in the HttpOnly cookie).
        // On local dev: include it so the frontend can store it in localStorage.
        String tokenForBody = localDev ? token : null;
        return new AuthResponse(tokenForBody, user.getId(), user.getEmail(),
                                user.getName(), user.getRole());
    }
}
