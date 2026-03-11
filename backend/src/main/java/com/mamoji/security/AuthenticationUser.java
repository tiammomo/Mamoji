package com.mamoji.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.lang.annotation.*;

/**
 * Shortcut annotation to inject authenticated {@code User} into controller methods.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal
public @interface AuthenticationUser {
}
