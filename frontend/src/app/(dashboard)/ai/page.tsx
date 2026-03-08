"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2, MessageCircle, Send, TrendingUp, Wallet } from "lucide-react";
import { aiApi, getErrorMessage } from "@/lib/api";
import type { AIStreamDonePayload } from "@/lib/api";

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
}

type StreamingMessage = ChatMessage & { role: "assistant" };

const NEAR_BOTTOM_THRESHOLD_PX = 64;
const STREAM_FLUSH_MS = 45;
const AUTO_SCROLL_THROTTLE_MS = 120;

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

function MessageBubble({ message }: { message: ChatMessage }) {
  return (
    <div className={`flex ${message.role === "user" ? "justify-end" : "justify-start"}`}>
      <div
        className={`max-w-[80%] rounded-2xl px-4 py-3 ${
          message.role === "user" ? "bg-indigo-600 text-white" : "bg-gray-100 text-gray-900"
        }`}
      >
        <p className="whitespace-pre-wrap">{message.content}</p>
        {message.role === "assistant" && message.warnings && message.warnings.length > 0 && (
          <p className="text-xs mt-2 text-amber-600">提示：{message.warnings.join("；")}</p>
        )}
        {message.role === "assistant" && message.sources && message.sources.length > 0 && (
          <p className="text-xs mt-1 text-gray-500">来源：{message.sources.join("，")}</p>
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
      await aiApi.chatStream(userContent, assistantType, {
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
        const fallback = await aiApi.chat(userContent, assistantType);
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
