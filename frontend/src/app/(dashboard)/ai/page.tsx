"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Bot, Loader2, MessageCircle, Send, Sparkles, TrendingUp, Wallet } from "lucide-react";
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
  accentClass: string;
  softClass: string;
  welcomeMessage: string;
  quickQuestions: string[];
}> = {
  finance: {
    name: "财务助手",
    icon: Wallet,
    description: "分析你的收支和预算执行情况",
    accentClass: "text-emerald-600 border-emerald-300 bg-emerald-50",
    softClass: "from-emerald-50 via-white to-teal-50",
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
    accentClass: "text-sky-600 border-sky-300 bg-sky-50",
    softClass: "from-sky-50 via-white to-blue-50",
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
  if (normalized.includes("tool call failed") || normalized.includes("tool_call_failed")) {
    return "工具调用失败，建议稍后重试。";
  }
  return warning;
}

function MessageBubble({ message, assistantType }: { message: ChatMessage; assistantType: AssistantType }) {
  const parsed = message.role === "assistant" ? parseAssistantContent(message.content) : null;
  const showStructured =
    message.role === "assistant" &&
    parsed !== null &&
    (parsed.metrics.length > 0 || parsed.bullets.length > 0 || parsed.details.length > 0);
  const isUser = message.role === "user";
  const assistantMeta = assistantConfig[assistantType];
  const AssistantIcon = assistantMeta.icon;

  return (
    <div className={`flex items-end gap-3 ${isUser ? "justify-end" : "justify-start"}`}>
      {!isUser && (
        <div
          className={`hidden h-9 w-9 shrink-0 items-center justify-center rounded-2xl border text-xs font-semibold shadow-sm sm:flex ${assistantMeta.accentClass}`}
        >
          <AssistantIcon className="h-4 w-4" />
        </div>
      )}
      <div
        className={`max-w-[80%] rounded-[22px] border px-4 py-3 shadow-sm ${
          isUser
            ? "border-indigo-500/40 bg-gradient-to-br from-indigo-600 to-indigo-700 text-white"
            : "border-slate-200/80 bg-white/95 text-slate-900"
        }`}
      >
        {!isUser && message.modeUsed && (
          <div className="mb-2 flex flex-wrap items-center gap-2 text-[11px]">
            <span className="rounded-full border border-indigo-200 bg-indigo-50 px-2.5 py-0.5 font-medium text-indigo-700">
              {message.modeUsed.toUpperCase()}
            </span>
            {message.traceId && <span className="text-slate-400">Trace #{message.traceId}</span>}
          </div>
        )}

        {showStructured && parsed ? (
          <div className="space-y-3.5">
            <p className="whitespace-pre-wrap text-[15px] leading-7 font-semibold text-slate-900">{parsed.summary}</p>
            {parsed.metrics.length > 0 && (
              <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
                {parsed.metrics.map((item, index) => (
                  <div
                    key={`${item.label}-${index}`}
                    className="rounded-xl border border-slate-200/80 bg-gradient-to-br from-slate-50 to-white px-3 py-2.5"
                  >
                    <p className="text-[11px] tracking-wide text-slate-500 uppercase">{item.label}</p>
                    <p className="mt-0.5 text-sm font-semibold text-slate-900">{item.value}</p>
                  </div>
                ))}
              </div>
            )}
            {parsed.bullets.length > 0 && (
              <ul className="space-y-1.5 text-sm">
                {parsed.bullets.map((item, index) => (
                  <li key={`${item}-${index}`} className="flex gap-2 text-slate-700">
                    <span className="mt-1.5 h-1.5 w-1.5 rounded-full bg-indigo-400" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            )}
            {parsed.details.length > 0 &&
              parsed.details.map((line, index) => (
                <p key={`${line}-${index}`} className="whitespace-pre-wrap text-sm leading-6 text-slate-600">
                  {line}
                </p>
              ))}
          </div>
        ) : (
          <p className={`whitespace-pre-wrap leading-7 ${isUser ? "text-white" : "text-slate-800"}`}>
            {message.content}
          </p>
        )}

        {!isUser && message.warnings && message.warnings.length > 0 && (
          <div className="mt-3 rounded-xl border border-amber-200 bg-amber-50/80 px-3 py-2 text-xs text-amber-900">
            <p className="mb-1 font-semibold">提示</p>
            <ul className="list-disc space-y-1 pl-4">
              {message.warnings.map((warning) => (
                <li key={warning}>{formatWarningLabel(warning)}</li>
              ))}
            </ul>
          </div>
        )}

        {!isUser && message.sources && message.sources.length > 0 && (
          <details className="mt-2 rounded-xl border border-slate-200 bg-slate-50/60 px-3 py-2 text-xs text-slate-600">
            <summary className="cursor-pointer list-none font-medium text-slate-700">来源 ({message.sources.length})</summary>
            <ul className="mt-2 space-y-1.5">
              {message.sources.map((source) => (
                <li key={source} className="break-all">
                  {source}
                </li>
              ))}
            </ul>
          </details>
        )}

        <p className={`mt-1 text-[11px] ${isUser ? "text-indigo-200" : "text-slate-400"}`}>
          {message.timestamp.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}
        </p>
      </div>
      {isUser && (
        <div className="hidden h-9 w-9 shrink-0 items-center justify-center rounded-2xl border border-indigo-300/60 bg-indigo-50 text-xs font-semibold text-indigo-700 sm:flex">
          我
        </div>
      )}
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
    () => messages.map((message) => <MessageBubble key={message.id} message={message} assistantType={assistantType} />),
    [messages, assistantType]
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
    <div className="relative px-4 py-5 sm:px-6 lg:px-8">
      <div className="pointer-events-none absolute inset-x-0 top-0 h-64 bg-[radial-gradient(circle_at_15%_0%,rgba(37,99,235,0.12),transparent_42%),radial-gradient(circle_at_85%_0%,rgba(16,185,129,0.10),transparent_38%)]" />

      <div className="relative space-y-4">
        <section className={`rounded-3xl border border-slate-200/80 bg-gradient-to-r p-5 shadow-sm ${currentConfig.softClass}`}>
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h1 className="flex items-center gap-3 text-2xl font-semibold text-slate-900">
                <MessageCircle className="h-7 w-7 text-indigo-600" />
                AI 助手
              </h1>
              <p className="mt-1 text-sm text-slate-600">{currentConfig.description}</p>
            </div>
            <div className="flex items-center gap-2 rounded-2xl border border-slate-200 bg-white/80 px-3 py-1.5 text-xs text-slate-500">
              <Sparkles className="h-3.5 w-3.5 text-indigo-500" />
              实时流式回答
            </div>
          </div>

          <div className="mt-4 flex flex-wrap gap-2">
            {(Object.keys(assistantConfig) as AssistantType[]).map((type) => {
              const config = assistantConfig[type];
              const Icon = config.icon;
              const active = assistantType === type;
              return (
                <button
                  key={type}
                  onClick={() => handleAssistantChange(type)}
                  className={`flex items-center gap-2 rounded-2xl border px-4 py-2 text-sm font-medium transition-all ${
                    active
                      ? `${config.accentClass} shadow-sm`
                      : "border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50"
                  }`}
                >
                  <Icon className="h-4 w-4" />
                  <span>{config.name}</span>
                </button>
              );
            })}
          </div>
        </section>

        <div className="flex flex-wrap gap-2">
          {modeOptions.map((option) => (
            <button
              key={option.mode}
              type="button"
              onClick={() => setChatMode(option.mode)}
              disabled={loading}
              className={`rounded-xl border px-3 py-1.5 text-sm transition-all ${
                chatMode === option.mode
                  ? "border-indigo-300 bg-indigo-50 text-indigo-700 shadow-sm"
                  : "border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50"
              } disabled:opacity-60`}
              title={option.description}
            >
              <span className="font-medium">{option.label}</span>
            </button>
          ))}
        </div>

        <section className="flex h-[calc(100vh-16.5rem)] min-h-[560px] flex-col overflow-hidden rounded-[28px] border border-slate-200/80 bg-white/95 shadow-[0_28px_68px_-36px_rgba(15,23,42,0.45)]">
          <div
            ref={messageListRef}
            onScroll={updateStickToBottomFlag}
            className="flex-1 overflow-y-auto px-4 py-5 sm:px-6 sm:py-6"
          >
            <div className="space-y-4">
              {renderedHistory}
              {streamingMessage && <MessageBubble message={streamingMessage} assistantType={assistantType} />}

              {loading && awaitingFirstChunk && (
                <div className="flex justify-start">
                  <div className="max-w-[80%] rounded-[22px] border border-slate-200 bg-white px-4 py-3 text-slate-600 shadow-sm">
                    <div className="flex items-center gap-2">
                      <Loader2 className="h-4 w-4 animate-spin text-indigo-500" />
                      <span className="text-sm">正在思考并组织答案...</span>
                    </div>
                  </div>
                </div>
              )}

              <div ref={messagesEndRef} />
            </div>
          </div>

          {messages.length <= 1 && !loading && !streamingMessage && (
            <div className="border-t border-slate-100 px-4 py-4 sm:px-6">
              <p className="mb-2 flex items-center gap-2 text-sm text-slate-500">
                <Bot className="h-4 w-4" />
                你可以这样问
              </p>
              <div className="flex flex-wrap gap-2">
                {currentConfig.quickQuestions.map((question) => (
                  <button
                    key={question}
                    onClick={() => void handleSend(question)}
                    className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1.5 text-sm text-slate-700 transition-colors hover:border-slate-300 hover:bg-white"
                  >
                    {question}
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="border-t border-slate-100 bg-white/90 px-4 py-4 sm:px-6">
            <div className="flex gap-3">
              <input
                type="text"
                value={input}
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={`问 ${currentConfig.name}...`}
                className="h-12 flex-1 rounded-2xl border border-slate-200 bg-slate-50 px-4 text-sm text-slate-800 placeholder:text-slate-400 focus:border-indigo-300 focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-200"
                disabled={loading}
              />
              <button
                onClick={() => void handleSend()}
                disabled={!input.trim() || loading}
                className="inline-flex h-12 items-center gap-2 rounded-2xl bg-indigo-600 px-5 text-sm font-medium text-white transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <Send className="h-4 w-4" />
                <span className="hidden sm:inline">发送</span>
              </button>
            </div>
            <p className="mt-2 text-xs text-slate-400">按 Enter 发送，Shift + Enter 换行</p>
          </div>
        </section>
      </div>
    </div>
  );
}

