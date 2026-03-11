package com.mamoji;

import com.mamoji.ai.AiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot application entrypoint for Mamoji backend.
 */
@SpringBootApplication
@EnableConfigurationProperties(AiProperties.class)
public class MamojiApplication {

    /**
     * Main bootstrap method.
     */
    public static void main(String[] args) {
        SpringApplication.run(MamojiApplication.class, args);
    }
}
