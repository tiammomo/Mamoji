package com.mamoji.security;

import com.mamoji.entity.User;
import com.mamoji.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // SSE endpoints use async dispatch; auth must be applied on async dispatch as well.
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // 跳过 OPTIONS 预检请求
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }

            String authHeader = request.getHeader("Authorization");

            log.debug("JWT Filter - Path: {}, AuthHeader: {}", request.getRequestURI(), authHeader != null ? "present" : "null");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.debug("JWT Filter - No valid auth header, continuing filter chain");
                filterChain.doFilter(request, response);
                return;
            }

            String token = authHeader.substring(7);
            log.debug("JWT Filter - Token: {}", token.substring(0, Math.min(10, token.length())) + "...");

            if (jwtService.validateToken(token)) {
                Long userId = jwtService.extractUserId(token);
                log.debug("JWT Filter - UserId: {}", userId);

                User user = userRepository.findById(userId).orElse(null);

                if (user != null) {
                    log.debug("JWT Filter - User found: {}", user.getEmail());
                    UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            Collections.emptyList()
                        );
                    authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                    );
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("JWT Filter - Authentication set in SecurityContext");
                } else {
                    log.warn("JWT Filter - User not found for id: {}, returning 401", userId);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":401,\"message\":\"用户不存在或已删除\"}");
                    return;
                }
            } else {
                log.warn("JWT Filter - Invalid token");
            }
        } catch (Exception e) {
            log.error("JWT Filter - Exception: {}", e.getMessage(), e);
        }

        filterChain.doFilter(request, response);
    }
}
