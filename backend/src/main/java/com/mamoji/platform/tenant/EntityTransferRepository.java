package com.mamoji.platform.tenant;

import java.util.List;

/** Persistence port for tenant-scoped, append-only entity transfers. */
public interface EntityTransferRepository {
    List<EntityTransfer> findAccessible(List<Long> accessibleEntityIds, Long entityId);

    boolean existsBetween(long firstEntityId, long secondEntityId);

    EntityTransfer append(EntityTransfer transfer);
}
