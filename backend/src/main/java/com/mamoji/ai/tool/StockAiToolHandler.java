package com.mamoji.ai.tool;

import com.mamoji.agent.tool.stock.StockTools;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Stock tool handler that routes operation names to {@link StockTools}.
 */
@Component
@RequiredArgsConstructor
public class StockAiToolHandler implements AiToolHandler {

    private final StockTools stockTools;

    /**
     * Tool namespace.
     */
    @Override
    public String name() {
        return "stock";
    }

    /**
     * Executes one stock operation.
     */
    @Override
    public AiToolResult execute(Long userId, Map<String, Object> params) {
        String operation = stringParam(params, "operation");
        if (operation == null || operation.isBlank()) {
            return AiToolResult.fail("stock", "operation is required");
        }

        return switch (operation) {
            case "query_market_index" -> AiToolResult.ok("stock.query_market_index", stockTools.queryMarketIndex());
            case "query_stock_quote" -> {
                String code = normalizeStockCode(stringParam(params, "stockCode"));
                yield AiToolResult.ok("stock.query_stock_quote", stockTools.queryStockQuote(code));
            }
            case "search_stock" -> AiToolResult.ok("stock.search_stock",
                stockTools.searchStock(stringParamOrDefault(params, "keyword", "")));
            case "get_stock_news" -> AiToolResult.ok("stock.get_stock_news",
                stockTools.getStockNews(stringParam(params, "stockCode")));
            default -> AiToolResult.fail("stock." + operation, "unsupported stock operation");
        };
    }

    /**
     * Reads string parameter.
     */
    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * Reads string parameter with default.
     */
    private String stringParamOrDefault(Map<String, Object> params, String key, String defaultValue) {
        String value = stringParam(params, key);
        return value != null ? value : defaultValue;
    }

    /**
     * Normalizes empty stock code to default benchmark code.
     */
    private String normalizeStockCode(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return "000001";
        }
        return stockCode;
    }
}

