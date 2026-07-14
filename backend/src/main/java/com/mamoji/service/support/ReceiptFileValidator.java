package com.mamoji.service.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ReceiptFileValidator {
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int HEADER_SIZE = 16;

    public ValidatedReceiptFile validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidReceiptFileException("Receipt file is required");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidReceiptFileException("Receipt file must not exceed 10 MB");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().strip();
        String extension = extensionOf(filename);
        if (extension.isBlank()) {
            throw new InvalidReceiptFileException("Receipt file must have an allowed extension");
        }
        byte[] header;
        try (InputStream stream = file.getInputStream()) {
            header = stream.readNBytes(HEADER_SIZE);
        } catch (IOException ex) {
            throw new InvalidReceiptFileException("Receipt file could not be inspected", ex);
        }
        FileKind kind = detect(header);
        if (kind == null) {
            throw new InvalidReceiptFileException("Only genuine JPEG, PNG, GIF, WebP, or PDF files are allowed");
        }
        if (!kind.extensions.contains(extension)) {
            throw new InvalidReceiptFileException("Receipt file extension does not match its content");
        }
        String declaredType = normalizeContentType(file.getContentType());
        if (!declaredType.isBlank()
            && !declaredType.equals("application/octet-stream")
            && !kind.contentTypes.contains(declaredType)) {
            throw new InvalidReceiptFileException("Receipt file content type does not match its content");
        }
        return new ValidatedReceiptFile(filename, kind.canonicalContentType);
    }

    private FileKind detect(byte[] header) {
        if (startsWith(header, 0xff, 0xd8, 0xff)) {
            return FileKind.JPEG;
        }
        if (startsWith(header, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return FileKind.PNG;
        }
        if (startsWithAscii(header, "GIF87a") || startsWithAscii(header, "GIF89a")) {
            return FileKind.GIF;
        }
        if (startsWithAscii(header, "RIFF") && asciiAt(header, 8, "WEBP")) {
            return FileKind.WEBP;
        }
        if (startsWithAscii(header, "%PDF-")) {
            return FileKind.PDF;
        }
        return null;
    }

    private boolean startsWith(byte[] value, int... expected) {
        if (value.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((value[index] & 0xff) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWithAscii(byte[] value, String expected) {
        return asciiAt(value, 0, expected);
    }

    private boolean asciiAt(byte[] value, int offset, String expected) {
        if (value.length < offset + expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if ((value[offset + index] & 0xff) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private String extensionOf(String filename) {
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        if (dot <= separator || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String value) {
        if (value == null) {
            return "";
        }
        int parameters = value.indexOf(';');
        return (parameters >= 0 ? value.substring(0, parameters) : value).strip().toLowerCase(Locale.ROOT);
    }

    private enum FileKind {
        JPEG(Set.of("jpg", "jpeg"), Set.of("image/jpeg", "image/pjpeg"), "image/jpeg"),
        PNG(Set.of("png"), Set.of("image/png"), "image/png"),
        GIF(Set.of("gif"), Set.of("image/gif"), "image/gif"),
        WEBP(Set.of("webp"), Set.of("image/webp"), "image/webp"),
        PDF(Set.of("pdf"), Set.of("application/pdf"), "application/pdf");

        private final Set<String> extensions;
        private final Set<String> contentTypes;
        private final String canonicalContentType;

        FileKind(Set<String> extensions, Set<String> contentTypes, String canonicalContentType) {
            this.extensions = extensions;
            this.contentTypes = contentTypes;
            this.canonicalContentType = canonicalContentType;
        }
    }

    public record ValidatedReceiptFile(String originalFilename, String contentType) {
    }

    public static class InvalidReceiptFileException extends IllegalArgumentException {
        public InvalidReceiptFileException(String message) {
            super(message);
        }

        public InvalidReceiptFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
