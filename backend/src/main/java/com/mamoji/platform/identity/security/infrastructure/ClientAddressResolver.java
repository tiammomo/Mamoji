package com.mamoji.platform.identity.security.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves a bounded IP literal and trusts proxy headers only when explicitly configured. */
@Component
public class ClientAddressResolver {
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]{2,64}");
    private final boolean trustForwardedHeaders;

    public ClientAddressResolver(
        @Value("${mamoji.security.auth.trust-forwarded-headers:false}") boolean trustForwardedHeaders
    ) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    public String resolve(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwarded = firstForwardedAddress(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                return forwarded;
            }
            String realAddress = normalizedAddress(request.getHeader("X-Real-IP"));
            if (realAddress != null) {
                return realAddress;
            }
        }
        String remoteAddress = normalizedAddress(request.getRemoteAddr());
        return remoteAddress == null ? "unknown" : remoteAddress;
    }

    private String firstForwardedAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizedAddress(value.split(",", 2)[0]);
    }

    private String normalizedAddress(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (!IP_LITERAL.matcher(normalized).matches()) {
            return null;
        }
        if (normalized.contains(":")) {
            try {
                return InetAddress.getByName(normalized).getHostAddress().toLowerCase(Locale.ROOT);
            } catch (UnknownHostException ignored) {
                return null;
            }
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        try {
            int[] parsed = Arrays.stream(octets).mapToInt(Integer::parseInt).toArray();
            if (Arrays.stream(parsed).anyMatch(octet -> octet < 0 || octet > 255)) {
                return null;
            }
            return parsed[0] + "." + parsed[1] + "." + parsed[2] + "." + parsed[3];
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
