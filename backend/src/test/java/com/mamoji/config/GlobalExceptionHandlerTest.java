package com.mamoji.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        this.mockMvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
    }

    @Test
    void shouldReturnTraceIdCodeAndMessageForAuthError() throws Exception {
        mockMvc.perform(get("/test/auth").header("X-Trace-Id", "trace-123"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.traceId").value("trace-123"))
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    @Test
    void shouldReturnUnifiedPayloadForForbidden() throws Exception {
        mockMvc.perform(get("/test/forbidden"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("You do not have permission to access this resource."));
    }

    @Test
    void shouldReturnValidationMessageForInvalidBody() throws Exception {
        mockMvc.perform(
                post("/test/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("name: must not be blank"));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/auth")
        String auth() {
            throw new BadCredentialsException("bad credentials");
        }

        @GetMapping("/test/forbidden")
        String forbidden() {
            throw new AccessDeniedException("no permission");
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody ValidateBody body) {
            return body.name;
        }
    }

    static class ValidateBody {

        @NotBlank
        public String name;
    }
}
