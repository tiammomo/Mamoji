package com.mamoji.evidence.application;

import com.mamoji.service.support.ObjectStorageService.StoredObject;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks whether a newly written object obtained its durable database reference. */
public final class ReceiptStorageWrite {
    private final StoredObject storedObject;
    private final AtomicBoolean referenced = new AtomicBoolean();

    public ReceiptStorageWrite(StoredObject storedObject) {
        this.storedObject = Objects.requireNonNull(storedObject, "storedObject");
    }

    public StoredObject storedObject() {
        return storedObject;
    }

    public void markReferenced() {
        referenced.set(true);
    }

    boolean referenced() {
        return referenced.get();
    }
}
