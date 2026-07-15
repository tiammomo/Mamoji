package com.mamoji.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PagedResponseTest {

    @Test
    void veryLargePageReturnsEmptyContentWithoutOffsetOverflow() {
        PagedResponse<Integer> response = PagedResponse.of(
            List.of(1, 2, 3),
            new PageRequest(Integer.MAX_VALUE, PageRequest.MAX_SIZE)
        );

        assertTrue(response.content.isEmpty());
        assertEquals(3, response.totalElements);
        assertEquals(Integer.MAX_VALUE, response.number);
        assertEquals(PageRequest.MAX_SIZE, response.size);
    }
}
