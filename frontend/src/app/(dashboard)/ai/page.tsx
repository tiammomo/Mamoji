"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2, MessageCircle, Send, TrendingUp, Wallet } from "lucide-react";
import { aiApi, getErrorMessage } from "@/lib/api";
import type { AIChatMode, AIStreamDonePayload } from "@/lib/api";

type AssistantType = "finance" | "stock";

interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: Date;
  warnings?: string[];
  sources?: string[];
  actions?: string[];
  usage?: Record<string, unknown>;
  modeUsed?: AIChatMode;
  traceId?: string;
}

type StreamingMessage = ChatMessage & { role: "assistant" };

const NEAR_BOTTOM_THRESHOLD_PX = 64;
const STREAM_FLUSH_MS = 45;
const AUTO_SCROLL_THROTTLE_MS = 120;
const modeOptions: Array<{ mode: AIChatMode; label: string; description: string }> = [
  { mode: "auto", label: "Auto", description: "Smart routing" },
  { mode: "llm", label: "LLM", description: "Direct model answer" },
  { mode: "agent", label: "Agent", description: "Tool-assisted reasoning" },
];

const assistantConfig: Record<AssistantType, {
  name: string;
  description: string;
  icon: typeof Wallet;
  welcomeMessage: string;
  quickQuestions: string[];
}> = {
  finance: {
    name: "财务助手",
    icon: Wallet,
    description: "分析你的收支和预算执行情况",
    welcomeMessage:
      "你好，我是财务助手。我可以帮你分析收支结构、预算执行进度，并给出理财建议。",
    quickQuestions: [
      "我这个月支出情况怎么样？",
      "我的预算执行率如何？",
      "哪个分类支出最多？",
      "给我一些本月节流建议。",
    ],
  },
  stock: {
    name: "股票助手",
    icon: TrendingUp,
    description: "提供市场观察和投资学习建议",
    welcomeMessage:
      "你好，我是股票助手。我可以帮你做基础市场观察与信息整理。投资有风险，请谨慎决策。",
    quickQuestions: [
      "今天大盘表现如何？",
      "消费板块近期怎么样？",
      "如何做分散投资？",
      "给我一个学习型的选股思路。",
    ],
  },
};

type MetricPair = {
  label: string;
  value: string;
};

type ParsedAssistantContent = {
  summary: string;
  metrics: MetricPair[];
  bullets: string[];
  details: string[];
};

function splitMetricLine(rawLine: string): MetricPair | null {
  const trimmed = rawLine.trim();
  if (!trimmed) {
    return null;
  }
  const line = trimmed.startsWith("- ") ? trimmed.slice(2).trim() : trimmed;
  const delimiter = line.includes("：") ? "：" : line.includes(":") ? ":" : null;
  if (!delimiter) {
    return null;
  }
  const separatorIndex = line.indexOf(delimiter);
  if (separatorIndex <= 0 || separatorIndex >= line.length - 1) {
    return null;
  }
  const label = line.slice(0, separatorIndex).trim();
  const value = line.slice(separatorIndex + 1).trim();
  if (!label || !value || label.length > 24) {
    return null;
  }
  return { label, value };
}

function parseAssistantContent(content: string): ParsedAssistantContent {
  const lines = content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  if (lines.length === 0) {
    return { summary: "", metrics: [], bullets: [], details: [] };
  }

  const summary = lines[0];
  const metrics: MetricPair[] = [];
  const bullets: string[] = [];
  const details: string[] = [];

  for (const line of lines.slice(1)) {
    const metric = splitMetricLine(line);
    if (metric && line.startsWith("-")) {
      metrics.push(metric);
      continue;
    }
    if (line.startsWith("-")) {
      bullets.push(line.slice(1).trim());
      continue;
    }
    if (!(line.endsWith("：") || line.endsWith(":"))) {
      details.push(line);
    }
  }

  return { summary, metrics, bullets, details };
}

function formatWarningLabel(warning: string): string {
  const normalized = warning.trim().toLowerCase();
  if (normalized === "schema_parse_failed") {
    return "模型输出格式异常，已自动使用兜底回答。";
  }
  if (normalized === "schema_repair_retry") {
    return "模型输出格式异常，已自动修复后返回。";
  }
  if (normalized === "internal_error") {
    return "系统处理异常，请稍后再试。";
  }
  if (normalized.includes("tool call failed")) {
    return "工具调用失败，建议稍后重试。";
  }
  return warning;
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const parsed = message.role === "assistant" ? parseAssistantContent(message.content) : null;
  const showStructured =
    message.role === "assistant" &&
    parsed !== null &&
    (parsed.metrics.length > 0 || parsed.bullets.length > 0 || parsed.details.length > 0);

  return (
    <div className={`flex ${message.role === "user" ? "justify-end" : "justify-start"}`}>
      <div
        className={`max-w-[80%] rounded-2xl px-4 py-3 ${
          message.role === "user" ? "bg-indigo-600 text-white" : "bg-gray-100 text-gray-900"
        }`}
      >
        {message.role === "assistant" && message.modeUsed && (
          <div className="mb-2 flex flex-wrap gap-2 text-[11px]">
            <span className="rounded-full border border-indigo-200 bg-indigo-50 px-2 py-0.5 text-indigo-700">
              {message.modeUsed.toUpperCase()}
            </span>
          </div>
        )}

        {showStructured && parsed ? (
          <div className="space-y-3">
            <p className="whitespace-pre-wrap leading-7 font-medium">{parsed.summary}</p>
            {parsed.metrics.length > 0 && (
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {parsed.metrics.map((item, index) => (
                  <div key={`${item.label}-${index}`} className="rounded-lg bg-white/80 px-3 py-2">
                    <p className="text-xs text-gray-500">{item.label}</p>
                    <p className="text-sm font-semibold">{item.value}</p>
                  </div>
                ))}
              </div>
            )}
            {parsed.bullets.length > 0 && (
              <ul className="list-disc space-y-1 pl-5 text-sm">
                {parsed.bullets.map((item, index) => (
                  <li key={`${item}-${index}`}>{item}</li>
                ))}
              </ul>
            )}
            {parsed.details.length > 0 &&
              parsed.details.map((line, index) => (
                <p key={`${line}-${index}`} className="whitespace-pre-wrap text-sm leading-6 text-gray-700">
                  {line}
                </p>
              ))}
          </div>
        ) : (
          <p className="whitespace-pre-wrap leading-7">{message.content}</p>
        )}

        {message.role === "assistant" && message.warnings && message.warnings.length > 0 && (
          <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
            <p className="mb-1 font-medium">提示</p>
            <ul className="list-disc space-y-1 pl-4">
              {message.warnings.map((warning) => (
                <li key={warning}>{formatWarningLabel(warning)}</li>
              ))}
            </ul>
          </div>
        )}

        {message.role === "assistant" && message.sources && message.sources.length > 0 && (
          <div className="mt-2 rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs text-gray-600">
            <p className="mb-1 font-medium text-gray-700">来源</p>
            <ul className="space-y-1">
              {message.sources.map((source) => (
                <li key={source} className="break-all">
                  {source}
                </li>
              ))}
            </ul>
          </div>
        )}

        <p className={`text-xs mt-1 ${message.role === "user" ? "text-indigo-200" : "text-gray-400"}`}>
          {message.timestamp.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}
        </p>
      </div>
    </div>
  );
}
export default function AIPage() {
  const router = useRouter();
  const messageListRef = useRef<HTMLDivElement>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const stickToBottomRef = useRef(true);
  const lastAutoScrollAtRef = useRef(0);

  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [streamingMessage, setStreamingMessage] = useState<StreamingMessage | null>(null);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [awaitingFirstChunk, setAwaitingFirstChunk] = useState(false);
  const [assistantType, setAssistantType] = useState<AssistantType>("finance");
  const [chatMode, setChatMode] = useState<AIChatMode>("auto");

  const currentConfig = useMemo(() => assistantConfig[assistantType], [assistantType]);

  const renderedHistory = useMemo(
    () => messages.map((message) => <MessageBubble key={message.id} message={message} />),
    [messages]
  );

  const updateStickToBottomFlag = (): void => {
    const container = messageListRef.current;
    if (!container) {
      return;
    }
    const distanceToBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
    stickToBottomRef.current = distanceToBottom < NEAR_BOTTOM_THRESHOLD_PX;
  };

  const maybeStickToBottom = (): void => {
    if (!stickToBottomRef.current) {
      return;
    }
    const now = Date.now();
    if (now - lastAutoScrollAtRef.current < AUTO_SCROLL_THROTTLE_MS) {
      return;
    }
    lastAutoScrollAtRef.current = now;
    requestAnimationFrame(() => {
      const container = messageListRef.current;
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    });
  };

  useEffect(() => {
    const token = localStorage.getItem("token");
    if (!token) {
      router.push("/login");
      return;
    }
    setMessages([
      {
        id: "welcome",
        role: "assistant",
        content: currentConfig.welcomeMessage,
        timestamp: new Date(),
      },
    ]);
    setStreamingMessage(null);
    setAwaitingFirstChunk(false);
  }, [router, assistantType, currentConfig.welcomeMessage]);

  useEffect(() => {
    if (!loading && !streamingMessage && stickToBottomRef.current) {
      messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }
  }, [messages.length, loading, streamingMessage]);

  async function handleSend(message?: string): Promise<void> {
    const userContent = (message ?? input).trim();
    if (!userContent || loading) {
      return;
    }

    const userMessage: ChatMessage = {
      id: `${Date.now()}-user`,
      role: "user",
      content: userContent,
      timestamp: new Date(),
    };

    setMessages((prev) => [...prev, userMessage]);
    setInput("");
    setLoading(true);
    setAwaitingFirstChunk(true);
    stickToBottomRef.current = true;

    const assistantId = `${Date.now()}-assistant`;
    const assistantTimestamp = new Date();
    setStreamingMessage({
      id: assistantId,
      role: "assistant",
      content: "",
      timestamp: assistantTimestamp,
    });
    maybeStickToBottom();

    let streamChunkCount = 0;
    let chunkBuffer = "";
    let flushTimer: ReturnType<typeof setTimeout> | null = null;
    let assembledContent = "";
    let donePayload: AIStreamDonePayload = {
      done: true,
      warnings: [],
      sources: [],
      actions: [],
      usage: {},
    };

    const flushChunks = (): void => {
      if (!chunkBuffer) {
        return;
      }
      const append = chunkBuffer;
      chunkBuffer = "";
      assembledContent += append;
      setStreamingMessage((prev) => (prev ? { ...prev, content: `${prev.content}${append}` } : prev));
      maybeStickToBottom();
    };

    const scheduleFlush = (): void => {
      if (flushTimer) {
        return;
      }
      flushTimer = setTimeout(() => {
        flushTimer = null;
        flushChunks();
      }, STREAM_FLUSH_MS);
    };

    try {
      await aiApi.chatStream(userContent, assistantType, chatMode, {
        onChunk: (chunk) => {
          streamChunkCount += 1;
          if (streamChunkCount === 1) {
            setAwaitingFirstChunk(false);
          }
          chunkBuffer += chunk;
          scheduleFlush();
        },
        onDone: (payload) => {
          donePayload = payload;
        },
      });

      if (flushTimer) {
        clearTimeout(flushTimer);
        flushTimer = null;
      }
      flushChunks();

      if (streamChunkCount === 0) {
        const fallback = await aiApi.chat(userContent, assistantType, chatMode);
        assembledContent = fallback.reply;
        setStreamingMessage((prev) => (prev ? { ...prev, content: fallback.reply } : prev));
      }

      const finalAssistantMessage: ChatMessage = {
        id: assistantId,
        role: "assistant",
        content: assembledContent,
        timestamp: assistantTimestamp,
        warnings: donePayload.warnings,
        sources: donePayload.sources,
        actions: donePayload.actions,
        usage: donePayload.usage,
        modeUsed: donePayload.modeUsed,
        traceId: donePayload.traceId,
      };

      setMessages((prev) => [...prev, finalAssistantMessage]);
      setStreamingMessage(null);
      maybeStickToBottom();
    } catch (error: unknown) {
      if (flushTimer) {
        clearTimeout(flushTimer);
        flushTimer = null;
      }
      setStreamingMessage(null);
      setMessages((prev) => [
        ...prev,
        {
          id: `${Date.now()}-error`,
          role: "assistant",
          content: getErrorMessage(error, "抱歉，我暂时无法回答，请稍后重试。"),
          timestamp: new Date(),
        },
      ]);
      maybeStickToBottom();
    } finally {
      setAwaitingFirstChunk(false);
      setLoading(false);
    }
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLInputElement>): void {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void handleSend();
    }
  }

  function handleAssistantChange(type: AssistantType): void {
    if (loading) {
      return;
    }
    setAssistantType(type);
    setMessages([]);
    setStreamingMessage(null);
    setAwaitingFirstChunk(false);
  }

  return (
    <div className="p-6 lg:p-8">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-3">
          <MessageCircle className="w-7 h-7 text-indigo-600" />
          AI 助手
        </h1>
        <p className="text-gray-500 mt-1">{currentConfig.description}</p>
      </div>

      <div className="flex gap-3 mb-6">
        {(Object.keys(assistantConfig) as AssistantType[]).map((type) => {
          const config = assistantConfig[type];
          const Icon = config.icon;
          return (
            <button
              key={type}
              onClick={() => handleAssistantChange(type)}
              className={`flex items-center gap-2 px-4 py-2 rounded-xl transition-all ${
                assistantType === type ? "bg-indigo-600 text-white" : "bg-white text-gray-700 hover:bg-gray-100 border"
              }`}
            >
              <Icon className="w-5 h-5" />
              <span className="font-medium">{config.name}</span>
            </button>
          );
        })}
      </div>

      <div className="flex flex-wrap gap-2 mb-6">
        {modeOptions.map((option) => (
          <button
            key={option.mode}
            type="button"
            onClick={() => setChatMode(option.mode)}
            disabled={loading}
            className={`px-3 py-1.5 rounded-lg border text-sm transition-colors ${
              chatMode === option.mode
                ? "bg-indigo-50 border-indigo-500 text-indigo-700"
                : "bg-white border-gray-300 text-gray-600 hover:bg-gray-50"
            } disabled:opacity-60`}
            title={option.description}
          >
            {option.label}
          </button>
        ))}
      </div>

      <div className="bg-white rounded-2xl shadow-sm border flex flex-col h-[calc(100vh-18rem)]">
        <div
          ref={messageListRef}
          onScroll={updateStickToBottomFlag}
          className="flex-1 overflow-y-auto p-6 space-y-4"
        >
          {renderedHistory}
          {streamingMessage && <MessageBubble message={streamingMessage} />}

          {loading && awaitingFirstChunk && (
            <div className="flex justify-start">
              <div className="bg-gray-100 rounded-2xl px-4 py-3">
                <div className="flex items-center gap-2">
                  <Loader2 className="w-4 h-4 animate-spin text-gray-500" />
                  <span className="text-gray-500">思考中...</span>
                </div>
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {messages.length <= 1 && !loading && !streamingMessage && (
          <div className="px-6 pb-4">
            <p className="text-sm text-gray-500 mb-2">可以这样问：</p>
            <div className="flex flex-wrap gap-2">
              {currentConfig.quickQuestions.map((question) => (
                <button
                  key={question}
                  onClick={() => void handleSend(question)}
                  className="text-sm px-3 py-1.5 bg-gray-100 text-gray-700 rounded-full hover:bg-gray-200"
                >
                  {question}
                </button>
              ))}
            </div>
          </div>
        )}

        <div className="border-t p-4">
          <div className="flex gap-3">
            <input
              type="text"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={`问 ${currentConfig.name}...`}
              className="flex-1 px-4 py-3 border rounded-xl focus:outline-none focus:ring-2 focus:ring-indigo-500"
              disabled={loading}
            />
            <button
              onClick={() => void handleSend()}
              disabled={!input.trim() || loading}
              className="px-6 py-3 bg-indigo-600 text-white rounded-xl hover:bg-indigo-700 disabled:opacity-50 flex items-center gap-2"
            >
              <Send className="w-5 h-5" />
              <span className="hidden sm:inline">发送</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

