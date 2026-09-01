package com.mamoji.platform.identity.security.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class ClientAddressResolverTest {
    @Test
    void ignoresSpoofableForwardedHeadersByDefault() {
        HttpServletRequest request = request("10.0.0.8", "198.51.100.20, 10.0.0.2", "198.51.100.21");

        assertEquals("10.0.0.8", new ClientAddressResolver(false).resolve(request));
    }

    @Test
    void usesTheFirstValidAddressBehindAnExplicitlyTrustedProxy() {
        HttpServletRequest request = request("10.0.0.8", " 2001:db8::4, 10.0.0.2", "198.51.100.21");

        assertEquals("2001:db8:0:0:0:0:0:4", new ClientAddressResolver(true).resolve(request));
    }

    @Test
    void rejectsArbitraryForwardedValues() {
        HttpServletRequest request = request("10.0.0.8", "attacker.example", "also-invalid");

        assertEquals("10.0.0.8", new ClientAddressResolver(true).resolve(request));
    }

    @Test
    void canonicalizesEquivalentIpRepresentations() {
        HttpServletRequest compressed = request("10.0.0.8", "2001:db8::4", null);
        HttpServletRequest expanded = request("10.0.0.8", "2001:0db8:0:0:0:0:0:4", null);

        ClientAddressResolver resolver = new ClientAddressResolver(true);
        assertEquals(resolver.resolve(compressed), resolver.resolve(expanded));
    }

    private HttpServletRequest request(String remoteAddress, String forwardedFor, String realIp) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddress);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        when(request.getHeader("X-Real-IP")).thenReturn(realIp);
        return request;
    }
}
