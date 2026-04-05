package com.nexflow.nexflow_backend.security;

import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserService userService;
    private final JwtTokenProvider jwtProvider;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${app.local-dev:false}")
    private boolean localDev;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();

        String googleId = oauth2User.getAttribute("sub");
        String email    = oauth2User.getAttribute("email");
        String name     = oauth2User.getAttribute("name");

        NexUser user  = userService.findOrCreateGoogleUser(googleId, email, name);
        String  token = jwtProvider.generateToken(user);

        log.info("[OAuth2] Google login successful: userId={} email={}", user.getId(), email);

        if (localDev) {
            // Local dev: pass token as URL param (same as before — no HTTPS required)
            getRedirectStrategy().sendRedirect(request, response,
                    frontendUrl + "/login?token=" + token);
        } else {
            // Production: HttpOnly cookie — token never touches the URL or JS
            ResponseCookie cookie = ResponseCookie.from("nexflow_token", token)
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("None")        // required for cross-domain (Vercel ↔ EC2)
                    .path("/")
                    .maxAge(Duration.ofDays(7))
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            getRedirectStrategy().sendRedirect(request, response, frontendUrl + "/");
        }
    }
}
