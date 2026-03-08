import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  accountApi,
  aiApi,
  api,
  getErrorMessage,
  receiptApi,
  statsApi,
  transactionApi,
} from "../lib/api";

global.fetch = vi.fn();

describe("API Client", () => {
  beforeEach(() => {
    vi.clearAllMocks();

    vi.stubGlobal("localStorage", {
      getItem: vi.fn(),
      removeItem: vi.fn(),
    });
  });

  it("should make GET request and return data", async () => {
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ data: { id: 1, name: "Test" } })),
    });

    const result = await api.get<{ id: number; name: string }>("/test");

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/v1/test",
      expect.objectContaining({ method: "GET" })
    );
    expect(result).toEqual({ id: 1, name: "Test" });
  });

  it("should include authorization header when token exists", async () => {
    vi.mocked(localStorage.getItem).mockReturnValue("test-token");
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ data: {} })),
    });

    await api.get("/test");

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/v1/test",
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: "Bearer test-token" }),
      })
    );
  });

  it("should clear auth info on 401/403", async () => {
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 401,
      text: () => Promise.resolve(JSON.stringify({ message: "Unauthorized" })),
    });

    await expect(api.get("/protected")).rejects.toThrow("Unauthorized");
    expect(localStorage.removeItem).toHaveBeenCalledWith("token");
    expect(localStorage.removeItem).toHaveBeenCalledWith("user");
  });

  it("should prefer 429 fallback message", async () => {
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 429,
      text: () => Promise.resolve(JSON.stringify({ message: "server raw" })),
    });

    await expect(api.get("/limited")).rejects.toThrow("请求过于频繁，请稍后再试");
  });

  it("should prefer 5xx fallback message", async () => {
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 500,
      text: () => Promise.resolve(JSON.stringify({ message: "server raw" })),
    });

    await expect(api.get("/broken")).rejects.toThrow("服务器错误，请稍后重试");
  });

  it("should send FormData without explicit Content-Type", async () => {
    const file = new File(["demo"], "demo.txt", { type: "text/plain" });
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ data: { id: 1 } })),
    });

    await receiptApi.upload(file);

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/v1/receipts/upload",
      expect.objectContaining({
        method: "POST",
      })
    );
    const [, requestOptions] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect((requestOptions.headers as Record<string, string>)["Content-Type"]).toBeUndefined();
  });

  it("should parse plain JSON when response has no data wrapper", async () => {
    const plainResponse = { ok: true };
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify(plainResponse)),
    });

    const result = await api.get<{ ok: boolean }>("/plain");
    expect(result).toEqual(plainResponse);
  });

  it("getErrorMessage should return fallback for unknown error", () => {
    expect(getErrorMessage(null, "fallback")).toBe("fallback");
    expect(getErrorMessage({ message: "" }, "fallback")).toBe("fallback");
    expect(getErrorMessage({ message: "oops" }, "fallback")).toBe("oops");
  });
});

describe("Domain API wrappers", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("transactionApi should build query params", async () => {
    const mockData = { total: 10, page: 1, pageSize: 20, list: [] };
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ data: mockData })),
    });

    await transactionApi.getTransactions({
      page: 1,
      pageSize: 20,
      type: 2,
      startDate: "2024-01-01",
      endDate: "2024-12-31",
    });

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/transactions?page=1&pageSize=20&type=2&startDate=2024-01-01&endDate=2024-12-31"),
      expect.any(Object)
    );
  });

  it("accountApi should get accounts", async () => {
    const mockAccounts = [{ id: 1, name: "现金", balance: 1000 }];
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ data: mockAccounts })),
    });

    const result = await accountApi.getAccounts();
    expect(result).toEqual(mockAccounts);
  });

  it("statsApi should build month query", async () => {
    const mockOverview = { income: 10000, expense: 5000, balance: 5000 };
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ data: mockOverview })),
    });

    await statsApi.getOverview("2024-01");

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/v1/stats/overview?month=2024-01",
      expect.any(Object)
    );
  });

  it("aiApi.chatStream should parse chunk and done events", async () => {
    vi.mocked(localStorage.getItem).mockReturnValue("stream-token");
    const streamChunks = [
      "event: chunk\ndata: {\"content\":\"你好\"}\n\n",
      "event: chunk\ndata: {\"content\":\"，世界\"}\n\n",
      "event: done\ndata: {\"done\":true,\"warnings\":[\"w1\"],\"sources\":[\"s1\"],\"actions\":[\"a1\"],\"usage\":{\"estimatedTokens\":5}}\n\n",
    ];
    const encoder = new TextEncoder();
    const body = {
      getReader: () => {
        let index = 0;
        return {
          read: vi.fn(async () => {
            if (index >= streamChunks.length) {
              return { done: true, value: undefined };
            }
            const value = encoder.encode(streamChunks[index]);
            index += 1;
            return { done: false, value };
          }),
        };
      },
    };

    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      body,
      text: () => Promise.resolve(""),
    });

    const chunks: string[] = [];
    let donePayload: unknown;

    await aiApi.chatStream("hello", "finance", "agent", {
      onChunk: (chunk) => chunks.push(chunk),
      onDone: (payload) => {
        donePayload = payload;
      },
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/v1/ai/chat/stream",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          Authorization: "Bearer stream-token",
        }),
      })
    );
    expect(chunks.join("")).toBe("你好，世界");
    expect(donePayload).toEqual({
      done: true,
      warnings: ["w1"],
      sources: ["s1"],
      actions: ["a1"],
      usage: { estimatedTokens: 5 },
    });
  });
});
