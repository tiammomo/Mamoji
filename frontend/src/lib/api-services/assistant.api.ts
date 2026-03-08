import { API_BASE, api } from "../api.client";
import type { AIChatResponse, AIStreamDonePayload } from "../api.types";

type StreamHandlers = {
  onChunk: (chunk: string) => void;
  onDone: (payload: AIStreamDonePayload) => void;
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
    });
  }
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
  chat: (message: string, assistantType?: string) => api.post<AIChatResponse>("/ai/chat", { message, assistantType }),
  chatStream: async (
    message: string,
    assistantType: string | undefined,
    handlers: StreamHandlers
  ): Promise<void> => {
    const token = getToken();
    const response = await fetch(`${API_BASE}/ai/chat/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ message, assistantType }),
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
