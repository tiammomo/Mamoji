"use client";
import { useState } from "react";
import { Form, Input, Button, Message } from "@arco-design/web-react";
import { IconEmail, IconLock, IconUser } from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/lib/stores/authStore";
import { useTranslations } from "next-intl";
import Link from "next/link";

const FormItem = Form.Item;

const AVATAR_PRESETS = [
  { emoji: "😊", color: "#6366f1" },
  { emoji: "🐱", color: "#ec4899" },
  { emoji: "🐶", color: "#f59e0b" },
  { emoji: "🐼", color: "#10b981" },
  { emoji: "🦊", color: "#f97316" },
  { emoji: "🐰", color: "#8b5cf6" },
  { emoji: "🐻", color: "#3b82f6" },
  { emoji: "🦁", color: "#ef4444" },
  { emoji: "🐨", color: "#06b6d4" },
  { emoji: "🐯", color: "#84cc16" },
];

export default function RegisterPage() {
  const [loading, setLoading] = useState(false);
  const [selectedAvatar, setSelectedAvatar] = useState(AVATAR_PRESETS[0]);
  const [inviteToken] = useState(() => {
    if (typeof window === "undefined") {
      return "";
    }
    const params = new URLSearchParams(window.location.search);
    return params.get("invite") || params.get("inviteToken") || "";
  });
  const router = useRouter();
  const { register } = useAuthStore();
  const t = useTranslations("auth");

  const handleSubmit = async (values: { email: string; password: string; nickname: string }) => {
    setLoading(true);
    try {
      await register({
        ...values,
        avatar: `${selectedAvatar.emoji}|${selectedAvatar.color}`,
        inviteToken: inviteToken || undefined,
      });
      Message.success("注册成功");
      router.push("/dashboard");
    } catch {
      Message.error(inviteToken ? "注册失败，请确认邀请链接和邮箱是否匹配" : "注册失败，邮箱可能已被使用");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex" style={{ backgroundColor: "var(--bg-color-page)" }}>
      {/* Left side - decorative */}
      <div
        className="hidden lg:flex lg:w-1/2 xl:w-3/5 relative overflow-hidden"
        style={{ background: "linear-gradient(135deg, #10b981 0%, #059669 100%)" }}
      >
        <div className="absolute inset-0 flex flex-col items-center justify-center p-12 text-white">
          <div className="text-8xl mb-6">🚀</div>
          <h1 className="text-4xl font-bold mb-4 text-center">创建企业工作台</h1>
          <p className="text-xl text-center opacity-90 max-w-md">
            让团队从第一天就看清人、钱和税
          </p>
          <div className="mt-12 space-y-4 text-left max-w-sm">
            {[
              { emoji: "✅", text: "人员入职离职可追踪" },
              { emoji: "📈", text: "收入成本趋势可分析" },
              { emoji: "🎯", text: "公司预算风险可预警" },
              { emoji: "🏦", text: "资金账户与税费协同" },
            ].map((item, i) => (
              <div key={i} className="flex items-center gap-3 bg-white/10 backdrop-blur-sm rounded-xl px-4 py-3">
                <span className="text-xl">{item.emoji}</span>
                <span>{item.text}</span>
              </div>
            ))}
          </div>
        </div>
        {/* Decorative circles */}
        <div className="absolute -bottom-20 -right-20 w-64 h-64 rounded-full bg-white/10" />
        <div className="absolute -top-10 -left-10 w-48 h-48 rounded-full bg-white/10" />
      </div>

      {/* Right side - form */}
      <div className="w-full lg:w-1/2 xl:w-2/5 flex items-center justify-center p-8">
        <div className="w-full max-w-md animate-fade-in">
          {/* Mobile logo */}
          <div className="lg:hidden text-center mb-8">
            <div
              className="w-16 h-16 rounded-2xl flex items-center justify-center text-4xl mx-auto mb-4"
              style={{ background: "linear-gradient(135deg, #10b981 0%, #059669 100%)" }}
            >
              🚀
            </div>
            <h1 className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
              创建账户
            </h1>
          </div>

          <div className="mb-8">
            <h2 className="text-2xl font-bold mb-2" style={{ color: "var(--text-color-1)" }}>
              {t("registerTitle")}
            </h2>
            <p style={{ color: "var(--text-color-3)" }}>
              {inviteToken ? "使用邀请链接创建团队账户" : "创建公司账户开始管理经营数据"}
            </p>
          </div>

          <Form layout="vertical" onSubmit={handleSubmit} autoComplete="off">
            {/* Avatar selector */}
            <FormItem label="选择头像">
              <div className="flex flex-wrap gap-3">
                {AVATAR_PRESETS.map((preset) => (
                  <button
                    key={preset.emoji}
                    type="button"
                    onClick={() => setSelectedAvatar(preset)}
                    className="w-12 h-12 rounded-xl flex items-center justify-center cursor-pointer text-2xl transition-all"
                    style={{
                      backgroundColor: preset.color + "20",
                      border: selectedAvatar.emoji === preset.emoji
                        ? `2px solid ${preset.color}`
                        : "2px solid transparent",
                      transform: selectedAvatar.emoji === preset.emoji ? "scale(1.1)" : "scale(1)",
                    }}
                  >
                    {preset.emoji}
                  </button>
                ))}
              </div>
            </FormItem>

            <FormItem
              field="nickname"
              rules={[{ required: true, message: "请输入昵称" }]}
            >
              <Input
                prefix={<IconUser style={{ color: "var(--text-color-4)" }} />}
                placeholder={t("nickname")}
                size="large"
                style={{ height: 48, borderRadius: 12 }}
              />
            </FormItem>

            <FormItem
              field="email"
              rules={[
                { required: true, message: "请输入邮箱" },
                { type: "email", message: "请输入有效的邮箱" },
              ]}
            >
              <Input
                prefix={<IconEmail style={{ color: "var(--text-color-4)" }} />}
                placeholder={t("email")}
                size="large"
                style={{ height: 48, borderRadius: 12 }}
              />
            </FormItem>

            <FormItem
              field="password"
              rules={[
                { required: true, message: "请输入密码" },
                { minLength: 12, message: "密码至少12位" },
              ]}
            >
              <Input.Password
                prefix={<IconLock style={{ color: "var(--text-color-4)" }} />}
                placeholder={t("password")}
                size="large"
                style={{ height: 48, borderRadius: 12 }}
              />
            </FormItem>

            <FormItem
              field="confirmPassword"
              rules={[
                { required: true, message: "请确认密码" },
                {
                  validator: (v, cb) => {
                    const form = v?.$form;
                    if (form && v !== form.getFieldValue("password")) {
                      cb(t("passwordMismatch"));
                    } else {
                      cb();
                    }
                  },
                },
              ]}
            >
              <Input.Password
                prefix={<IconLock style={{ color: "var(--text-color-4)" }} />}
                placeholder={t("confirmPassword")}
                size="large"
                style={{ height: 48, borderRadius: 12 }}
              />
            </FormItem>

            <FormItem>
              <Button
                type="primary"
                htmlType="submit"
                long
                size="large"
                loading={loading}
                style={{
                  height: 48,
                  borderRadius: 12,
                  fontSize: 16,
                  fontWeight: 600,
                  background: "linear-gradient(135deg, #10b981 0%, #059669 100%)",
                  border: "none",
                }}
              >
                {t("register")}
              </Button>
            </FormItem>
          </Form>

          <div className="text-center mt-6">
            <span style={{ color: "var(--text-color-3)" }}>{t("hasAccount")} </span>
            <Link
              href="/login"
              className="font-medium hover:underline"
              style={{ color: "var(--color-primary)" }}
            >
              {t("login")}
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
