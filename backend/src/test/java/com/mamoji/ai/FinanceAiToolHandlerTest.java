package com.mamoji.ai;

import com.mamoji.agent.tool.finance.FinanceTools;
import com.mamoji.ai.tool.AiToolResult;
import com.mamoji.ai.tool.FinanceAiToolHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

class FinanceAiToolHandlerTest {

    @Test
    void shouldPassUserIdToFinanceTools() {
        FinanceTools financeTools = Mockito.mock(FinanceTools.class);
        Mockito.when(financeTools.queryIncomeExpense(7L, null, null)).thenReturn("{\"ok\":true}");
        FinanceAiToolHandler handler = new FinanceAiToolHandler(financeTools);

        AiToolResult result = handler.execute(7L, Map.of("operation", "query_income_expense"));

        Assertions.assertTrue(result.success());
        Mockito.verify(financeTools).queryIncomeExpense(7L, null, null);
    }
}
