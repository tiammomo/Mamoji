package com.mamoji.service.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class WebhookUrlValidatorTest {

    @Test
    void acceptsAResolvablePublicHttpsUrl() {
        WebhookUrlValidator validator = validatorFor("8.8.8.8");

        assertDoesNotThrow(() -> validator.requireSafeUrl("https://hooks.example.test/events"));
    }

    @Test
    void rejectsLoopbackPrivateLinkLocalAndMetadataAddresses() {
        assertUnsafe("http://localhost/hook", "127.0.0.1");
        assertUnsafe("https://hooks.example.test/hook", "10.20.30.40");
        assertUnsafe("https://hooks.example.test/hook", "169.254.169.254");
        assertUnsafe("https://hooks.example.test/hook", "168.63.129.16");
        assertUnsafe("https://hooks.example.test/hook", "fc00::1");
    }

    @Test
    void rejectsMixedPublicAndPrivateDnsAnswers() {
        WebhookUrlValidator validator = new WebhookUrlValidator(host -> new InetAddress[] {
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("192.168.1.20")
        });

        assertThrows(WebhookUrlValidator.UnsafeWebhookUrlException.class,
            () -> validator.requireSafeUrl("https://hooks.example.test/events"));
    }

    @Test
    void rejectsUnresolvedAndCredentialBearingUrls() {
        WebhookUrlValidator unresolved = new WebhookUrlValidator(host -> {
            throw new UnknownHostException(host);
        });

        assertThrows(WebhookUrlValidator.UnsafeWebhookUrlException.class,
            () -> unresolved.requireSafeUrl("https://missing.example.test/events"));
        assertThrows(WebhookUrlValidator.UnsafeWebhookUrlException.class,
            () -> validatorFor("8.8.8.8").requireSafeUrl("https://user:secret@hooks.example.test/events"));
    }

    private void assertUnsafe(String url, String address) {
        assertThrows(WebhookUrlValidator.UnsafeWebhookUrlException.class,
            () -> validatorFor(address).requireSafeUrl(url));
    }

    private WebhookUrlValidator validatorFor(String address) {
        return new WebhookUrlValidator(host -> new InetAddress[] { InetAddress.getByName(address) });
    }
}
