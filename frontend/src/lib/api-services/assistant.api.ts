import { API_BASE, api } from "../api.client";
import type { AIChatMode, AIChatResponse, AIStreamDonePayload } from "../api.types";

type StreamHandlers = {
  onChunk: (chunk: string) => void;
  onDone: (payload: AIStreamDonePayload) => void;
};

const MOCK_AI_ENABLED = process.env.NEXT_PUBLIC_AI_MOCK === "true";
const MOCK_STREAM_CHUNK_DELAY_MS = 50;

type MockResult = {
  reply: string;
  done: AIStreamDonePayload;
};

function getToken(): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  return localStorage.getItem("token");
}

function parseSseBlock(block: string, handlers: StreamHandlers): void {
  const lines = block.split(/\r?\n/);
  let event = "message";
  const dataLines: string[] = [];

  lines.forEach((line) => {
    if (line.startsWith("event:")) {
      event = line.slice(6).trim();
      return;
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  });

  if (dataLines.length === 0) {
    return;
  }

  const rawPayload = dataLines.join("\n");
  let parsed: unknown;
  try {
    parsed = JSON.parse(rawPayload);
  } catch {
    return;
  }

  if (event === "chunk") {
    const chunk = (parsed as { content?: unknown }).content;
    if (typeof chunk === "string") {
      handlers.onChunk(chunk);
    }
    return;
  }

  if (event === "done") {
    const payload = parsed as Record<string, unknown>;
    handlers.onDone({
      done: true,
      warnings: Array.isArray(payload.warnings) ? payload.warnings.filter((v): v is string => typeof v === "string") : [],
      sources: Array.isArray(payload.sources) ? payload.sources.filter((v): v is string => typeof v === "string") : [],
      actions: Array.isArray(payload.actions) ? payload.actions.filter((v): v is string => typeof v === "string") : [],
      usage: payload.usage && typeof payload.usage === "object" ? (payload.usage as Record<string, unknown>) : {},
      modeUsed:
        payload.modeUsed === "auto" || payload.modeUsed === "llm" || payload.modeUsed === "agent"
          ? payload.modeUsed
          : undefined,
      traceId: typeof payload.traceId === "string" ? payload.traceId : undefined,
    });
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

function chunkText(text: string, chunkSize = 22): string[] {
  if (!text) {
    return [];
  }
  const chunks: string[] = [];
  for (let i = 0; i < text.length; i += chunkSize) {
    chunks.push(text.slice(i, i + chunkSize));
  }
  return chunks;
}

function buildMockResult(message: string, assistantType?: string): MockResult {
  const normalized = message.trim();
  const now = new Date();
  const minute = now.getMinutes();

  if (assistantType === "stock") {
    const trend = minute % 2 === 0 ? "震荡偏强" : "窄幅震荡";
    const reply = [
      "以下为模拟答复（前端本地生成）：",
      `你问的是：“${normalized}”`,
      `当前观察：市场情绪${trend}，建议先看成交量与板块轮动再决策。`,
      "学习型建议：先设定止损位，再分批建仓，避免一次性重仓。",
      "风险提示：以上仅用于功能演示，不构成投资建议。",
    ].join("\n");

    return {
      reply,
      done: {
        done: true,
        warnings: ["模拟模式：数据非实时行情"],
        sources: ["mock://browser/stock-snapshot"],
        actions: ["review-risk", "set-stop-loss"],
        usage: { mode: "mock", assistantType: "stock", chars: reply.length },
      },
    };
  }

  const spendHint = 1200 + (minute % 7) * 180;
  const reply = [
    "以下为模拟答复（前端本地生成）：",
    `你问的是：“${normalized}”`,
    `本月可优化支出约 ¥${spendHint}，优先检查餐饮与冲动消费。`,
    "建议：采用 50/30/20 预算法，并每周复盘一次实际开销。",
    "如需，我可以继续给出“本周可执行的 3 条节流动作”。",
  ].join("\n");

  return {
    reply,
    done: {
      done: true,
      warnings: ["模拟模式：结果仅用于界面演示"],
      sources: ["mock://browser/finance-snapshot"],
      actions: ["set-weekly-budget", "reduce-variable-cost"],
      usage: { mode: "mock", assistantType: "finance", chars: reply.length },
    },
  };
}

async function throwStreamError(response: Response): Promise<never> {
  let message = `请求失败 (${response.status})`;
  try {
    const text = await response.text();
    if (text) {
      const data = JSON.parse(text) as { message?: unknown };
      if (typeof data.message === "string" && data.message.trim() !== "") {
        message = data.message;
      }
    }
  } catch {
    // Ignore parse errors.
  }

  if (response.status === 401 || response.status === 403) {
    if (typeof window !== "undefined") {
      localStorage.removeItem("token");
      localStorage.removeItem("user");
      if (!window.location.pathname.includes("/login")) {
        window.location.href = "/login";
      }
    }
  } else if (response.status === 429) {
    message = "请求过于频繁，请稍后再试";
  } else if (response.status >= 500) {
    message = "服务器错误，请稍后重试";
  }

  throw new Error(message);
}

export const aiApi = {
  chat: async (message: string, assistantType?: string, mode: AIChatMode = "auto"): Promise<AIChatResponse> => {
    if (MOCK_AI_ENABLED) {
      const result = buildMockResult(message, assistantType);
      return { reply: result.reply };
    }
    return api.post<AIChatResponse>("/ai/chat", { message, assistantType, mode });
  },
  chatStream: async (
    message: string,
    assistantType: string | undefined,
    mode: AIChatMode,
    handlers: StreamHandlers
  ): Promise<void> => {
    if (MOCK_AI_ENABLED) {
      const result = buildMockResult(message, assistantType);
      const chunks = chunkText(result.reply);
      for (const chunk of chunks) {
        handlers.onChunk(chunk);
        await sleep(MOCK_STREAM_CHUNK_DELAY_MS);
      }
      handlers.onDone(result.done);
      return;
    }

    const token = getToken();
    const response = await fetch(`${API_BASE}/ai/chat/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ message, assistantType, mode }),
    });

    if (!response.ok) {
      await throwStreamError(response);
    }

    if (!response.body) {
      throw new Error("流式响应不可用");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const result = await reader.read();
      if (result.done) {
        break;
      }
      buffer += decoder.decode(result.value, { stream: true }).replace(/\r\n/g, "\n");

      let separator = buffer.indexOf("\n\n");
      while (separator >= 0) {
        const block = buffer.slice(0, separator).trim();
        buffer = buffer.slice(separator + 2);
        if (block) {
          parseSseBlock(block, handlers);
        }
        separator = buffer.indexOf("\n\n");
      }
    }

    const finalBlock = buffer.trim();
    if (finalBlock) {
      parseSseBlock(finalBlock, handlers);
    }
  },
};
