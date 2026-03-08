package com.mamoji.ai;

import com.mamoji.ai.tool.AiToolResult;
import com.mamoji.ai.tool.AiToolRouter;
import com.mamoji.ai.tool.SpringAiToolCallingBridge;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class SpringAiToolCallingBridgeTest {

    @Test
    void shouldReturnEmptyWhenBridgeDisabled() {
        AiProperties properties = new AiProperties();
        properties.getToolCallingOps().setSpringEnabled(false);
        AiToolRouter router = Mockito.mock(AiToolRouter.class);
        SpringAiToolCallingBridge bridge = new SpringAiToolCallingBridge(properties, router);

        SpringAiToolCallingBridge.ToolCallingContext context = bridge.invoke("stock", "600519");

        Assertions.assertTrue(context.promptAddon().isEmpty());
        Mockito.verifyNoInteractions(router);
    }

    @Test
    void shouldInvokeStockToolWhenEnabled() {
        AiProperties properties = new AiProperties();
        properties.getToolCallingOps().setSpringEnabled(true);
        properties.getToolCallingOps().setStockEnabled(true);

        AiToolRouter router = Mockito.mock(AiToolRouter.class);
        Mockito.when(router.route(ArgumentMatchers.eq(0L), ArgumentMatchers.eq("stock"), ArgumentMatchers.anyMap()))
            .thenReturn(AiToolResult.ok("stock.query_stock_quote", "{\"price\":100}"));
        SpringAiToolCallingBridge bridge = new SpringAiToolCallingBridge(properties, router);

        SpringAiToolCallingBridge.ToolCallingContext context = bridge.invoke("stock", "看看600519");

        Assertions.assertTrue(context.promptAddon().contains("Tool result(JSON):"));
        Assertions.assertEquals(1, context.actions().size());
        Assertions.assertTrue(context.warnings().isEmpty());
    }
}
