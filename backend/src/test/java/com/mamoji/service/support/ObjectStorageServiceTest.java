package com.mamoji.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.service.support.ObjectStorageService.ObjectInventoryLimitExceededException;
import com.mamoji.service.support.ObjectStorageService.StoredObjectMetadata;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ObjectStorageServiceTest {

    @Test
    void listsObjectMetadataAcrossBuckets() throws Exception {
        MinioClient client = mock(MinioClient.class);
        Instant modified = Instant.parse("2026-06-01T12:00:00Z");
        Item item = item("receipts/company-1/receipt.pdf", 42, modified);
        Result<Item> result = result(item);
        when(client.listObjects(any(ListObjectsArgs.class)))
            .thenReturn(List.of(result), List.of());
        ObjectStorageService storage = storage(client, true);

        List<StoredObjectMetadata> inventory = storage.listObjects(
            Set.of("mamoji", "archive"),
            "receipts/",
            10
        );

        assertEquals(List.of(new StoredObjectMetadata(
            "archive", "receipts/company-1/receipt.pdf", 42, modified
        )), inventory);
        ArgumentCaptor<ListObjectsArgs> arguments = ArgumentCaptor.forClass(ListObjectsArgs.class);
        verify(client, times(2)).listObjects(arguments.capture());
        assertEquals(List.of("archive", "mamoji"), arguments.getAllValues().stream()
            .map(ListObjectsArgs::bucket)
            .toList());
        assertEquals(List.of("receipts/", "receipts/"), arguments.getAllValues().stream()
            .map(ListObjectsArgs::prefix)
            .toList());
        assertEquals(List.of(true, true), arguments.getAllValues().stream()
            .map(ListObjectsArgs::recursive)
            .toList());
    }

    @Test
    void refusesToReturnATruncatedInventory() throws Exception {
        MinioClient client = mock(MinioClient.class);
        Result<Item> first = result(item("receipts/1.pdf", 1, Instant.now()));
        Result<Item> second = result(item("receipts/2.pdf", 1, Instant.now()));
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(
            first,
            second
        ));
        ObjectStorageService storage = storage(client, true);

        assertThrows(
            ObjectInventoryLimitExceededException.class,
            () -> storage.listObjects(Set.of("mamoji"), "receipts/", 1)
        );
    }

    @Test
    void disabledStorageReturnsNoInventoryWithoutCallingMinio() {
        ObjectStorageService storage = storage(mock(MinioClient.class), false);

        assertEquals(List.of(), storage.listObjects(Set.of("mamoji"), "receipts/", 1));
    }

    private ObjectStorageService storage(MinioClient client, boolean enabled) {
        ObjectStorageService storage = new ObjectStorageService(
            mock(ReceiptFileValidator.class),
            enabled,
            "http://minio:9000",
            "access-key-123",
            "secret-key-123456",
            "mamoji",
            "us-east-1",
            "",
            600
        );
        ReflectionTestUtils.setField(storage, "minioClient", client);
        return storage;
    }

    @SuppressWarnings("unchecked")
    private Result<Item> result(Item item) throws Exception {
        Result<Item> result = mock(Result.class);
        when(result.get()).thenReturn(item);
        return result;
    }

    private Item item(String name, long size, Instant modified) {
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(name);
        when(item.size()).thenReturn(size);
        when(item.lastModified()).thenReturn(modified.atZone(ZoneOffset.UTC));
        return item;
    }
}
