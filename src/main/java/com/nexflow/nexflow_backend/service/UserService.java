package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.domain.UserRole;
import com.nexflow.nexflow_backend.repository.NexUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /** Max failed OTP attempts before the code is invalidated. */
    private static final int MAX_OTP_ATTEMPTS = 5;

    /** Cryptographically secure random — never use java.util.Random for security tokens. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final NexUserRepository userRepository;
    private final PasswordEncoder   passwordEncoder;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String smtpFrom;

    /**
     * When true: OTP is printed to the terminal (no SMTP needed for local development).
     * When false (production): OTP is sent via email only and never logged.
     */
    @Value("${app.local-dev:false}")
    private boolean localDev;

    // ── Registration ──────────────────────────────────────────────────────────

    public NexUser register(String email, String rawPassword, String name) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        if (rawPassword == null || rawPassword.length() < 6)
            throw new IllegalArgumentException("Password must be at least 6 characters");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name is required");

        if (userRepository.existsByEmail(email.toLowerCase())) {
            throw new IllegalArgumentException("An account with this email already exists");
        }

        NexUser user = new NexUser();
        user.setEmail(email.toLowerCase().trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setName(name.trim());
        user.setRole(firstUserGetsAdmin() ? UserRole.ADMIN : UserRole.MEMBER);
        user.setEmailVerified(false);

        String otp = generateOtp();
        user.setOtpCode(otp);
        user.setOtpExpiresAt(Instant.now().plusSeconds(900)); // 15 min
        user.setOtpFailedAttempts(0);

        user = userRepository.save(user);
        deliverOtp(user.getEmail(), otp, "NexFlow — your verification code",
                "Your NexFlow verification code is: " + otp + "\n\nThis code expires in 15 minutes.",
                "OTP");
        log.info("[Auth] registered userId={} email={} role={}", user.getId(), user.getEmail(), user.getRole());
        return user;
    }

    // ── OTP Verification ──────────────────────────────────────────────────────

    public NexUser verifyOtp(String email, String otp) {
        NexUser user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        if (user.isEmailVerified()) {
            throw new IllegalArgumentException("Email is already verified");
        }
        if (user.getOtpCode() == null || user.getOtpExpiresAt() == null) {
            throw new IllegalArgumentException("No active verification code — please request a new one");
        }
        if (Instant.now().isAfter(user.getOtpExpiresAt())) {
            throw new IllegalArgumentException("Verification code has expired — please request a new one");
        }

        // Enforce brute-force limit
        if (user.getOtpFailedAttempts() >= MAX_OTP_ATTEMPTS) {
            user.setOtpCode(null);
            user.setOtpExpiresAt(null);
            userRepository.save(user);
            throw new IllegalArgumentException("Too many failed attempts. Please request a new verification code.");
        }

        // Constant-time comparison to avoid timing oracle
        if (!constantTimeEquals(user.getOtpCode(), otp.trim())) {
            user.setOtpFailedAttempts(user.getOtpFailedAttempts() + 1);
            userRepository.save(user);
            int remaining = MAX_OTP_ATTEMPTS - user.getOtpFailedAttempts();
            throw new IllegalArgumentException(
                    "Incorrect verification code" + (remaining > 0 ? " (" + remaining + " attempts remaining)" : ""));
        }

        user.setEmailVerified(true);
        user.setOtpCode(null);
        user.setOtpExpiresAt(null);
        user.setOtpFailedAttempts(0);
        return userRepository.save(user);
    }

    public void resendOtp(String email) {
        NexUser user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (user.isEmailVerified()) throw new IllegalArgumentException("Email is already verified");

        String otp = generateOtp();
        user.setOtpCode(otp);
        user.setOtpExpiresAt(Instant.now().plusSeconds(900));
        user.setOtpFailedAttempts(0);  // reset on fresh code
        userRepository.save(user);
        deliverOtp(user.getEmail(), otp, "NexFlow — your verification code",
                "Your NexFlow verification code is: " + otp + "\n\nThis code expires in 15 minutes.",
                "OTP");
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    public NexUser login(String email, String rawPassword) {
        NexUser user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        // Check password first — prevents revealing whether an email exists with an unverified account
        if (user.getPasswordHash() == null ||
                !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        if (!user.isEmailVerified()) {
            // Password is correct but email not verified — resend a fresh OTP automatically
            String otp = generateOtp();
            user.setOtpCode(otp);
            user.setOtpExpiresAt(Instant.now().plusSeconds(900));
            user.setOtpFailedAttempts(0);
            userRepository.save(user);
            deliverOtp(user.getEmail(), otp, "NexFlow — your verification code",
                    "Your NexFlow verification code is: " + otp + "\n\nThis code expires in 15 minutes.",
                    "OTP");
            throw new IllegalArgumentException("EMAIL_NOT_VERIFIED:" + user.getEmail());
        }

        log.info("[Auth] login success userId={} email={}", user.getId(), user.getEmail());
        return user;
    }

    // ── OAuth2 (Google) ───────────────────────────────────────────────────────

    public NexUser findOrCreateGoogleUser(String googleId, String email, String name) {
        return userRepository.findByGoogleId(googleId).orElseGet(() -> {
            NexUser user = userRepository.findByEmail(email.toLowerCase()).orElseGet(() -> {
                NexUser newUser = new NexUser();
                newUser.setEmail(email.toLowerCase().trim());
                newUser.setName(name != null ? name.trim() : email);
                newUser.setRole(firstUserGetsAdmin() ? UserRole.ADMIN : UserRole.MEMBER);
                newUser.setEmailVerified(true);
                return newUser;
            });
            user.setGoogleId(googleId);
            user.setEmailVerified(true);
            return userRepository.save(user);
        });
    }

    // ── Password reset ────────────────────────────────────────────────────────

    public void requestPasswordReset(String email) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        userRepository.findByEmail(email.toLowerCase()).ifPresent(user -> {
            String otp = generateOtp();
            user.setOtpCode(otp);
            user.setOtpExpiresAt(Instant.now().plusSeconds(900)); // 15 min
            user.setOtpFailedAttempts(0);
            userRepository.save(user);
            deliverOtp(user.getEmail(), otp,
                    "NexFlow — password reset code",
                    "Your NexFlow password reset code is: " + otp +
                    "\n\nThis code expires in 15 minutes." +
                    "\n\nIf you did not request a password reset, you can ignore this email.",
                    "Reset OTP");
        });
    }

    public void resetPassword(String email, String otp, String newPassword) {
        if (newPassword == null || newPassword.length() < 6)
            throw new IllegalArgumentException("Password must be at least 6 characters");

        NexUser user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        if (user.getOtpCode() == null || user.getOtpExpiresAt() == null)
            throw new IllegalArgumentException("No active reset code — please request a new one");
        if (Instant.now().isAfter(user.getOtpExpiresAt()))
            throw new IllegalArgumentException("Reset code has expired — please request a new one");

        if (user.getOtpFailedAttempts() >= MAX_OTP_ATTEMPTS) {
            user.setOtpCode(null);
            user.setOtpExpiresAt(null);
            userRepository.save(user);
            throw new IllegalArgumentException("Too many failed attempts. Please request a new reset code.");
        }

        if (!constantTimeEquals(user.getOtpCode(), otp.trim())) {
            user.setOtpFailedAttempts(user.getOtpFailedAttempts() + 1);
            userRepository.save(user);
            throw new IllegalArgumentException("Incorrect reset code");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setOtpCode(null);
        user.setOtpExpiresAt(null);
        user.setOtpFailedAttempts(0);
        user.setEmailVerified(true);
        userRepository.save(user);
        log.info("[Auth] Password reset successfully for {}", email);
    }

    // ── Admin operations ──────────────────────────────────────────────────────

    public NexUser updateRole(UUID targetUserId, UserRole newRole, NexUser requestingUser) {
        if (requestingUser.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("Only admins can change user roles");
        }
        NexUser target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        target.setRole(newRole);
        return userRepository.save(target);
    }

    public NexUser getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean firstUserGetsAdmin() {
        return userRepository.count() == 0;
    }

    /** Cryptographically secure 6-digit OTP. */
    private static String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    /** Constant-time string comparison — prevents timing oracle attacks on OTP checks. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Deliver an OTP to the user.
     *
     * Local dev  (app.local-dev=true):  always print to terminal regardless of SMTP state.
     * Production (app.local-dev=false): send via SMTP; if SMTP is misconfigured, log a
     *                                   warning but NEVER log the OTP value.
     */
    private void deliverOtp(String email, String otp, String subject, String body, String label) {
        if (localDev) {
            // Terminal output — safe for development, never committed or shipped
            System.out.println("╔══════════════════════════════════════╗");
            System.out.println("║  NexFlow Local Dev — " + label);
            System.out.println("║  Email : " + email);
            System.out.println("║  Code  : " + otp);
            System.out.println("╚══════════════════════════════════════╝");
            return;
        }

        // Production: SMTP only
        if (mailSender != null && smtpFrom != null && !smtpFrom.isBlank()) {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(smtpFrom);
                msg.setTo(email);
                msg.setSubject(subject);
                msg.setText(body);
                mailSender.send(msg);
                log.info("[Auth] {} email sent to {}", label, email);
            } catch (Exception e) {
                // Log the delivery failure but NEVER log the OTP value in production
                log.error("[Auth] Failed to send {} email to {}: {}. " +
                          "Check SMTP configuration — user cannot complete verification.", label, email, e.getMessage());
            }
        } else {
            log.error("[Auth] SMTP is not configured. Cannot deliver {} to {}. " +
                      "Set SMTP_HOST, SMTP_USERNAME, SMTP_PASSWORD environment variables.", label, email);
        }
    }
}
