"use client";

import { Suspense, useState } from "react";
import axios from "axios";
import { Alert, Button, Form, Input, Message } from "@arco-design/web-react";
import { IconEmail, IconLock, IconSafe } from "@arco-design/web-react/icon";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import AuthShell from "@/components/auth/AuthShell";
import { useAuthStore } from "@/lib/stores/authStore";

const FormItem = Form.Item;
const showDemoCredentials = process.env.NEXT_PUBLIC_SHOW_DEMO_CREDENTIALS !== "false";

type LoginValues = { email: string; password: string };

function safeNextPath(requestedPath: string | null) {
  if (!requestedPath || typeof window === "undefined") return "/dashboard";
  try {
    const target = new URL(requestedPath, window.location.origin);
    if (target.origin !== window.location.origin || !target.pathname.startsWith("/")) {
      return "/dashboard";
    }
    return `${target.pathname}${target.search}${target.hash}`;
  } catch {
    return "/dashboard";
  }
}

function loginErrorMessage(error: unknown) {
  if (!axios.isAxiosError(error)) return "登录失败，请稍后重试";
  if (error.response?.status === 429) return "尝试次数过多，请稍后再试";
  if (error.response?.status === 403) return "当前账户已被限制登录，请联系管理员";
  if (!error.response) return "无法连接服务器，请检查网络后重试";
  return "邮箱或密码不正确";
}

function LoginContent() {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm<LoginValues>();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { login } = useAuthStore();
  const t = useTranslations("auth");
  const sessionExpired = searchParams.get("reason") === "session_expired";

  const handleSubmit = async (values: LoginValues) => {
    setLoading(true);
    try {
      await login(values);
      Message.success("登录成功");
      router.replace(safeNextPath(searchParams.get("next")));
    } catch (error) {
      Message.error(loginErrorMessage(error));
    } finally {
      setLoading(false);
    }
  };

  const fillDemo = (email: string) => {
    form.setFieldsValue({ email, password: "123456" });
  };

  return (
    <AuthShell
      variant="login"
      title={t("loginTitle")}
      description="登录后继续处理当前主体的经营事项与待办。"
    >
      {sessionExpired && (
        <Alert className="mb-5" type="warning" content="会话已过期，请重新登录以继续。" />
      )}

      <Form form={form} layout="vertical" onSubmit={handleSubmit} autoComplete="on">
        <FormItem
          label={t("email")}
          field="email"
          rules={[
            { required: true, message: "请输入邮箱" },
            { type: "email", message: "请输入有效的邮箱" },
          ]}
        >
          <Input
            prefix={<IconEmail aria-hidden="true" />}
            placeholder="name@company.com"
            size="large"
            autoComplete="email"
          />
        </FormItem>

        <FormItem
          label={t("password")}
          field="password"
          rules={[{ required: true, message: "请输入密码" }]}
        >
          <Input.Password
            prefix={<IconLock aria-hidden="true" />}
            placeholder={t("password")}
            size="large"
            autoComplete="current-password"
          />
        </FormItem>

        <div className="mb-5 flex items-center gap-2 text-xs" style={{ color: "var(--text-color-3)" }}>
          <IconSafe aria-hidden="true" />
          <span>系统会在会话失效后自动清理本地凭据。</span>
        </div>

        <FormItem className="mb-0">
          <Button type="primary" htmlType="submit" long size="large" loading={loading}>
            {loading ? "正在验证…" : t("login")}
          </Button>
        </FormItem>
      </Form>

      <div className="auth-form-footer">
        <span>{t("noAccount")} </span>
        <Link href="/register">使用邀请创建账号</Link>
      </div>

      {showDemoCredentials && (
        <section className="demo-account-panel" aria-label="演示账号">
          <div>
            <strong>体验演示环境</strong>
            <span>一键填入本地演示账号；生产环境应关闭此区域。</span>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <Button type="secondary" onClick={() => fillDemo("test@mamoji.com")}>管理员视角</Button>
            <Button type="secondary" onClick={() => fillDemo("family@mamoji.com")}>成员视角</Button>
          </div>
        </section>
      )}
    </AuthShell>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={<div className="app-boot-screen"><div className="app-boot-mark">M</div></div>}>
      <LoginContent />
    </Suspense>
  );
}
