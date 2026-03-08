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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
    SecurityConfigProdTest.TestController.class,
    SecurityConfigProdTest.ActuatorController.class
})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "app.security.h2-console-enabled=false",
    "app.security.prometheus-public-enabled=false",
    "app.security.frame-options=deny",
    "app.security.cors.allowed-origins=https://app.example.com"
})
class SecurityConfigProdTest {

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
    void shouldBlockH2ConsolePathWhenDisabled() throws Exception {
        mockMvc.perform(get("/h2-console/test"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldUseDenyFrameOptionsInProd() throws Exception {
        mockMvc.perform(get("/api/test/ping"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void shouldRequireAuthenticationForPrometheusWhenPublicAccessDisabled() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isUnauthorized());
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
