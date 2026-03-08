import type { ApiError } from "./api.types";

export const API_BASE = process.env.NEXT_PUBLIC_API_BASE || "/api/v1";

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export function buildQueryString(params: Record<string, string | number | undefined>): string {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") {
      query.set(key, String(value));
    }
  });
  const queryString = query.toString();
  return queryString ? `?${queryString}` : "";
}

export function getErrorMessage(error: unknown, fallback: string): string {
  if (error && typeof error === "object" && "message" in error) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === "string" && message.trim() !== "") {
      return message;
    }
  }
  return fallback;
}

class ApiClient {
  constructor(private readonly baseUrl: string) {}

  private looksLikeHtml(text: string): boolean {
    const trimmed = text.trimStart();
    return trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html");
  }

  private getToken(): string | null {
    if (typeof window !== "undefined") {
      return localStorage.getItem("token");
    }
    return null;
  }

  private parseJsonSafely(text: string): unknown {
    if (!text) {
      return undefined;
    }
    try {
      return JSON.parse(text);
    } catch {
      return undefined;
    }
  }

  private handleError(response: Response, data?: unknown): never {
    const status = response.status;
    let message = "请求失败";

    if (data && typeof data === "object" && "message" in data) {
      const serverMessage = (data as { message?: unknown }).message;
      if (typeof serverMessage === "string" && serverMessage.trim() !== "") {
        message = serverMessage;
      }
    }

    if (status === 401 || status === 403) {
      if (typeof window !== "undefined") {
        localStorage.removeItem("token");
        localStorage.removeItem("user");
        if (!window.location.pathname.includes("/login")) {
          window.location.href = "/login";
        }
      }
    }

    if (status >= 500) {
      if (message === "请求失败" || message.trim() === "") {
        message = "服务器错误，请稍后重试";
      }
    } else if (status === 429) {
      message = "请求过于频繁，请稍后再试";
    }

    const error: ApiError = new Error(message);
    error.code = status;
    error.status = status;
    throw error;
  }

  private async request<T>(endpoint: string, options: RequestInit = {}, includeJsonContentType = true): Promise<T> {
    const token = this.getToken();
    const headers: HeadersInit = {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    };

    if (includeJsonContentType && !(headers as Record<string, string>)["Content-Type"]) {
      (headers as Record<string, string>)["Content-Type"] = "application/json";
    }

    const response = await fetch(`${this.baseUrl}${endpoint}`, {
      ...options,
      headers,
    });

    const text = await response.text();
    const parsedData = this.parseJsonSafely(text);

    if (this.looksLikeHtml(text)) {
      const error: ApiError = new Error("接口返回了 HTML，而不是 JSON。请检查后端服务地址和接口是否存在。");
      error.code = response.status;
      error.status = response.status;
      throw error;
    }

    if (!response.ok) {
      this.handleError(response, parsedData ?? { message: `请求失败 (${response.status})` });
    }

    if (!text) {
      return {} as T;
    }

    if (parsedData && typeof parsedData === "object" && "data" in parsedData) {
      return (parsedData as ApiResponse<T>).data;
    }

    return parsedData as T;
  }

  async get<T>(endpoint: string): Promise<T> {
    return this.request<T>(endpoint, { method: "GET" });
  }

  async post<T>(endpoint: string, data?: unknown): Promise<T> {
    return this.request<T>(endpoint, {
      method: "POST",
      body: data ? JSON.stringify(data) : undefined,
    });
  }

  async put<T>(endpoint: string, data?: unknown): Promise<T> {
    return this.request<T>(endpoint, {
      method: "PUT",
      body: data ? JSON.stringify(data) : undefined,
    });
  }

  async delete<T>(endpoint: string): Promise<T> {
    return this.request<T>(endpoint, { method: "DELETE" });
  }

  async postForm<T>(endpoint: string, formData: FormData): Promise<T> {
    return this.request<T>(
      endpoint,
      {
        method: "POST",
        body: formData,
      },
      false
    );
  }
}

export const api = new ApiClient(API_BASE);
