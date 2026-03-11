package com.mamoji.config;

import com.mamoji.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration for stateless JWT authentication.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityProperties securityProperties;

    /**
     * Configures security filter chain and endpoint authorization rules.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .headers(headers -> headers.frameOptions(this::configureFrameOptions))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                auth.requestMatchers("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/test").permitAll();
                auth.requestMatchers("/actuator/health").permitAll();
                if (securityProperties.isPrometheusPublicEnabled()) {
                    auth.requestMatchers("/actuator/prometheus").permitAll();
                }
                auth.requestMatchers("/error").permitAll();
                if (securityProperties.isH2ConsoleEnabled()) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    /**
     * Builds CORS configuration source from externalized security properties.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        SecurityProperties.Cors cors = securityProperties.getCors();
        List<String> allowedOrigins = cors.getAllowedOrigins() != null ? cors.getAllowedOrigins() : List.of();

        if (allowedOrigins.contains("*")) {
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            configuration.setAllowedOrigins(allowedOrigins);
        }

        configuration.setAllowedMethods(cors.getAllowedMethods());
        configuration.setAllowedHeaders(cors.getAllowedHeaders());
        configuration.setExposedHeaders(cors.getExposedHeaders());
        configuration.setAllowCredentials(cors.isAllowCredentials() && !allowedOrigins.contains("*"));
        configuration.setMaxAge(cors.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Applies frame-options policy based on configured mode.
     */
    private void configureFrameOptions(org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<HttpSecurity>.FrameOptionsConfig frame) {
        String mode = securityProperties.normalizedFrameOptions();
        if ("disable".equals(mode)) {
            frame.disable();
            return;
        }
        if ("sameorigin".equals(mode)) {
            frame.sameOrigin();
            return;
        }
        frame.deny();
    }

    /**
     * Password encoder bean for user credentials.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
