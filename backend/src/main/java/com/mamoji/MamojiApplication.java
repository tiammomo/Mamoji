package com.mamoji;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
@EnableScheduling
public class MamojiApplication {

    public static void main(String[] args) {
        System.setProperty("debug", System.getProperty("debug", "false"));
        SpringApplication.run(MamojiApplication.class, args);
    }

    @Bean
    WebMvcConfigurer corsConfigurer(
        @Value("${mamoji.security.cors.allowed-origins:http://localhost:33000,http://127.0.0.1:33000}") String allowedOrigins
    ) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toArray(String[]::new);
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (origins.length == 0) {
                    return;
                }
                registry.addMapping("/api/v1/**")
                    .allowedOrigins(origins)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("Authorization", "Content-Type", "X-Requested-With")
                    .exposedHeaders("Content-Disposition")
                    .maxAge(3600);
            }
        };
    }
}
