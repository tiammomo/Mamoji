package com.mamoji.config;

import com.mamoji.security.JwtAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
    SecurityConfigDevTest.TestController.class,
    SecurityConfigDevTest.ActuatorController.class
})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "app.security.h2-console-enabled=true",
    "app.security.prometheus-public-enabled=true",
    "app.security.frame-options=sameorigin",
    "app.security.cors.allowed-origins=http://localhost:33000,http://127.0.0.1:33000",
    "app.security.cors.allowed-methods=GET,POST,PUT,DELETE,OPTIONS",
    "app.security.cors.allowed-headers=*",
    "app.security.cors.exposed-headers=Authorization,Content-Type",
    "app.security.cors.allow-credentials=true",
    "app.security.cors.max-age-seconds=3600"
})
/**
 * Test suite for SecurityConfigDevTest.
 */
class SecurityConfigDevTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUpFilterPassThrough() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2, FilterChain.class);
            chain.doFilter(
                invocation.getArgument(0, ServletRequest.class),
                invocation.getArgument(1, ServletResponse.class)
            );
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any(FilterChain.class));
    }

    @Test
    void shouldPermitH2ConsolePathWhenEnabled() throws Exception {
        mockMvc.perform(get("/h2-console/test"))
            .andExpect(status().isNotFound());
    }

    @Test
    void shouldApplyCorsWhitelistForAllowedOrigin() throws Exception {
        mockMvc.perform(options("/api/test/ping")
                .header("Origin", "http://localhost:33000")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:33000"));
    }

    @Test
    void shouldRejectCorsForDisallowedOrigin() throws Exception {
        mockMvc.perform(options("/api/test/ping")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
    }

    @Test
    void shouldUseSameOriginFrameOptionsInDev() throws Exception {
        mockMvc.perform(get("/api/test/ping"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }

    @Test
    void shouldPermitPrometheusWhenPublicAccessEnabled() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isNotFound());
    }

    @RestController
    @RequestMapping("/api/test")
    public static class TestController {
        @GetMapping("/ping")
        public ResponseEntity<String> ping() {
            return ResponseEntity.ok("ok");
        }
    }

    @RestController
    @RequestMapping("/actuator")
    public static class ActuatorController {
        @GetMapping("/prometheus")
        public ResponseEntity<String> prometheus() {
            return ResponseEntity.ok("metrics");
        }
    }
}

