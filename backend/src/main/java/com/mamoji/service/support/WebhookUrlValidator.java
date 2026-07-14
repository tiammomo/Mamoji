package com.mamoji.service.support;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okhttp3.Dns;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebhookUrlValidator {
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
        "localhost",
        "metadata",
        "metadata.google.internal",
        "metadata.google",
        "instance-data",
        "instance-data.ec2.internal"
    );
    private static final Set<String> BLOCKED_METADATA_ADDRESSES = Set.of(
        "168.63.129.16",
        "169.254.169.254",
        "169.254.170.2",
        "100.100.100.200"
    );

    private final HostResolver resolver;

    @Autowired
    public WebhookUrlValidator() {
        this(InetAddress::getAllByName);
    }

    WebhookUrlValidator(HostResolver resolver) {
        this.resolver = resolver;
    }

    public URI requireSafeUrl(String value) {
        String candidate = value == null ? "" : value.strip();
        if (candidate.isBlank()) {
            throw new UnsafeWebhookUrlException("Webhook URL is required");
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException ex) {
            throw new UnsafeWebhookUrlException("Webhook URL is invalid", ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new UnsafeWebhookUrlException("Webhook URL must use http or https");
        }
        if (uri.isOpaque() || uri.getRawUserInfo() != null) {
            throw new UnsafeWebhookUrlException("Webhook URL must not contain user information");
        }
        String host = normalizeHost(uri.getHost());
        if (host.isBlank()) {
            throw new UnsafeWebhookUrlException("Webhook URL must contain a valid host");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new UnsafeWebhookUrlException("Webhook URL contains an invalid port");
        }
        try {
            requirePublicAddresses(host);
        } catch (UnknownHostException ex) {
            throw new UnsafeWebhookUrlException("Webhook host must resolve only to public addresses", ex);
        }
        return uri;
    }

    public Dns validatingDns() {
        return this::requirePublicAddresses;
    }

    List<InetAddress> requirePublicAddresses(String hostname) throws UnknownHostException {
        String normalized = normalizeHost(hostname);
        if (normalized.isBlank() || isBlockedHostname(normalized)) {
            throw unknownHost("Blocked webhook host: " + hostname, null);
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(normalized);
        } catch (UnknownHostException ex) {
            throw unknownHost("Webhook host cannot be resolved: " + hostname, ex);
        }
        if (addresses == null || addresses.length == 0) {
            throw unknownHost("Webhook host did not resolve to an address: " + hostname, null);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw unknownHost("Webhook host resolves to a non-public address", null);
            }
        }
        return List.copyOf(Arrays.asList(addresses));
    }

    private boolean isBlockedHostname(String host) {
        return BLOCKED_HOSTNAMES.contains(host) || host.endsWith(".localhost");
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address == null
            || address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()
            || BLOCKED_METADATA_ADDRESSES.contains(address.getHostAddress())) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (bytes.length == 16) {
            if (isIpv4Mapped(bytes)) {
                return isBlockedIpv4(Arrays.copyOfRange(bytes, 12, 16));
            }
            int first = unsigned(bytes[0]);
            return (first & 0xfe) == 0xfc;
        }
        return true;
    }

    private boolean isBlockedIpv4(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return true;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return true;
        }
        if (first == 169 && second == 254) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && second == 168) {
            return true;
        }
        if (first == 192 && second == 0 && (unsigned(bytes[2]) == 0 || unsigned(bytes[2]) == 2)) {
            return true;
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return true;
        }
        return (first == 198 && second == 51 && unsigned(bytes[2]) == 100)
            || (first == 203 && second == 0 && unsigned(bytes[2]) == 113);
    }

    private boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return unsigned(bytes[10]) == 0xff && unsigned(bytes[11]) == 0xff;
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private String normalizeHost(String hostname) {
        if (hostname == null) {
            return "";
        }
        String normalized = hostname.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private UnknownHostException unknownHost(String message, Exception cause) {
        UnknownHostException exception = new UnknownHostException(message);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public static class UnsafeWebhookUrlException extends IllegalArgumentException {
        public UnsafeWebhookUrlException(String message) {
            super(message);
        }

        public UnsafeWebhookUrlException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
