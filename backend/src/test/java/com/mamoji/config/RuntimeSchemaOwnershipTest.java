package com.mamoji.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RuntimeSchemaOwnershipTest {
    private static final Pattern RUNTIME_DDL = Pattern.compile(
        "(?i)\\b(?:CREATE(?:\\s+(?:UNIQUE|OR\\s+REPLACE))?|ALTER|DROP)\\s+"
            + "(?:TABLE|INDEX|SCHEMA|SEQUENCE|VIEW|TYPE|TRIGGER|FUNCTION)\\b"
    );

    @Test
    void applicationRuntimeContainsNoSchemaDdl() throws IOException {
        Path sourceRoot = Files.isDirectory(Path.of("src/main/java"))
            ? Path.of("src/main/java")
            : Path.of("backend/src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> recordViolation(sourceRoot, path, violations));
        }

        assertTrue(
            violations.isEmpty(),
            "Schema DDL belongs in Flyway migrations, not application runtime code: " + violations
        );
    }

    private void recordViolation(Path sourceRoot, Path path, List<String> violations) {
        try {
            if (RUNTIME_DDL.matcher(Files.readString(path)).find()) {
                violations.add(sourceRoot.relativize(path).toString());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect " + path, exception);
        }
    }
}
