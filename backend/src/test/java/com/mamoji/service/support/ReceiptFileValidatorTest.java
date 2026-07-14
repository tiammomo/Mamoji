package com.mamoji.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ReceiptFileValidatorTest {
    private final ReceiptFileValidator validator = new ReceiptFileValidator();

    @Test
    void acceptsFilesWhoseMagicBytesExtensionAndTypeAgree() {
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00
        };
        ReceiptFileValidator.ValidatedReceiptFile result = validator.validate(
            new MockMultipartFile("file", "invoice.png", "image/png", png)
        );

        assertEquals("image/png", result.contentType());
    }

    @Test
    void rejectsHtmlSvgAndScriptContentEvenWhenRenamed() {
        assertInvalid("invoice.png", "image/png", "<html><script>alert(1)</script></html>");
        assertInvalid("invoice.pdf", "application/pdf", "<svg xmlns='http://www.w3.org/2000/svg'></svg>");
        assertInvalid("invoice.jpg", "image/jpeg", "javascript:alert(1)");
    }

    @Test
    void rejectsMismatchedExtensionOrDeclaredContentType() {
        byte[] pdf = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);

        assertThrows(ReceiptFileValidator.InvalidReceiptFileException.class,
            () -> validator.validate(new MockMultipartFile("file", "invoice.png", "application/pdf", pdf)));
        assertThrows(ReceiptFileValidator.InvalidReceiptFileException.class,
            () -> validator.validate(new MockMultipartFile("file", "invoice.pdf", "image/png", pdf)));
    }

    private void assertInvalid(String filename, String contentType, String content) {
        assertThrows(ReceiptFileValidator.InvalidReceiptFileException.class,
            () -> validator.validate(new MockMultipartFile(
                "file",
                filename,
                contentType,
                content.getBytes(StandardCharsets.UTF_8)
            )));
    }
}
