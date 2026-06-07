package com.mamoji.service.support;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ObjectStorageService {
    private final boolean enabled;
    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final String externalUrl;
    private final int presignedUrlExpirySeconds;
    private MinioClient minioClient;
    private boolean bucketReady;

    public ObjectStorageService(
        @Value("${mamoji.object-storage.enabled:false}") boolean enabled,
        @Value("${mamoji.object-storage.endpoint:http://localhost:9000}") String endpoint,
        @Value("${mamoji.object-storage.access-key:minioadmin}") String accessKey,
        @Value("${mamoji.object-storage.secret-key:minioadmin}") String secretKey,
        @Value("${mamoji.object-storage.bucket:mamoji}") String bucket,
        @Value("${mamoji.object-storage.external-url:}") String externalUrl,
        @Value("${mamoji.object-storage.presigned-url-expiry-seconds:600}") int presignedUrlExpirySeconds
    ) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.externalUrl = externalUrl == null ? "" : externalUrl.strip();
        this.presignedUrlExpirySeconds = Math.max(60, Math.min(presignedUrlExpirySeconds, 604800));
    }

    public StoredObject storeReceiptFile(long companyId, MultipartFile file) {
        String objectKey = objectKey(companyId, file.getOriginalFilename());
        String contentType = file.getContentType() == null || file.getContentType().isBlank()
            ? "application/octet-stream"
            : file.getContentType();
        if (!enabled) {
            return new StoredObject("metadata_only", null, objectKey, null);
        }
        try (InputStream stream = file.getInputStream()) {
            ensureBucket();
            client().putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .stream(stream, file.getSize(), -1L)
                .contentType(contentType)
                .build());
            return new StoredObject("minio", bucket, objectKey, publicObjectUrl(objectKey));
        } catch (Exception ex) {
            throw new IllegalStateException("MinIO upload failed", ex);
        }
    }

    public Optional<String> presignedDownloadUrl(String provider, String storedBucket, String objectKey, String storedUrl) {
        if ("minio".equals(provider) && !isBlank(objectKey)) {
            if (!enabled) {
                return isBlank(storedUrl) ? Optional.empty() : Optional.of(storedUrl);
            }
            String targetBucket = isBlank(storedBucket) ? bucket : storedBucket;
            try {
                return Optional.of(client().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(targetBucket)
                    .object(objectKey)
                    .expiry(presignedUrlExpirySeconds, TimeUnit.SECONDS)
                    .build()));
            } catch (Exception ex) {
                throw new IllegalStateException("MinIO presigned URL failed", ex);
            }
        }
        if (!isBlank(storedUrl)) {
            return Optional.of(storedUrl);
        }
        return Optional.empty();
    }

    public int presignedUrlExpirySeconds() {
        return presignedUrlExpirySeconds;
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        boolean exists = client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client().makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        bucketReady = true;
    }

    private MinioClient client() {
        if (minioClient == null) {
            minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        }
        return minioClient;
    }

    private String objectKey(long companyId, String originalFilename) {
        LocalDate today = LocalDate.now();
        String safeName = sanitizeFilename(originalFilename);
        return "receipts/company-" + companyId + "/" + today.getYear() + "/" + String.format("%02d", today.getMonthValue())
            + "/" + UUID.randomUUID() + "-" + safeName;
    }

    private String sanitizeFilename(String originalFilename) {
        String value = originalFilename == null || originalFilename.isBlank() ? "receipt" : originalFilename;
        return value.replaceAll("[\\\\/\\r\\n\\t]", "_").replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private String publicObjectUrl(String objectKey) {
        if (externalUrl.isBlank()) {
            return null;
        }
        String base = externalUrl.endsWith("/") ? externalUrl.substring(0, externalUrl.length() - 1) : externalUrl;
        return base + "/" + bucket + "/" + encodePath(objectKey);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    }

    public record StoredObject(String provider, String bucket, String objectKey, String url) {
    }
}
