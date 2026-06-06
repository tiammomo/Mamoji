"use client";
import { useState } from "react";
import { Form, Input, Button, Message, Checkbox } from "@arco-design/web-react";
import { IconEmail, IconLock } from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/lib/stores/authStore";
import { useTranslations } from "next-intl";
import Link from "next/link";

const FormItem = Form.Item;

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  const { login } = useAuthStore();
  const t = useTranslations("auth");

  const handleSubmit = async (values: { email: string; password: string }) => {
    setLoading(true);
    try {
      await login(values);
      Message.success(t("login") + "成功");
      router.push("/dashboard");
    } catch {
      Message.error("邮箱或密码错误");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex" style={{ backgroundColor: "var(--bg-color-page)" }}>
      {/* Left side - decorative */}
      <div
        className="hidden lg:flex lg:w-1/2 xl:w-3/5 relative overflow-hidden"
        style={{ background: "var(--gradient-primary)" }}
      >
        <div className="absolute inset-0 flex flex-col items-center justify-center p-12 text-white">
          <div className="text-8xl mb-6">💰</div>
          <h1 className="text-4xl font-bold mb-4 text-center">Mamoji</h1>
          <p className="text-xl text-center opacity-90 max-w-md">
            初创公司经营助手，让收入、成本、税费和人员状态一眼清晰
          </p>
          <div className="mt-12 grid grid-cols-3 gap-6 text-center">
            <div className="bg-white/10 backdrop-blur-sm rounded-2xl p-4">
              <div className="text-3xl mb-2">📊</div>
              <div className="text-sm">经营分析</div>
            </div>
            <div className="bg-white/10 backdrop-blur-sm rounded-2xl p-4">
              <div className="text-3xl mb-2">🎯</div>
              <div className="text-sm">预算控制</div>
            </div>
            <div className="bg-white/10 backdrop-blur-sm rounded-2xl p-4">
              <div className="text-3xl mb-2">🧾</div>
              <div className="text-sm">税费提醒</div>
            </div>
          </div>
        </div>
        {/* Decorative circles */}
        <div className="absolute -bottom-20 -left-20 w-64 h-64 rounded-full bg-white/10" />
        <div className="absolute -top-10 -right-10 w-48 h-48 rounded-full bg-white/10" />
        <div className="absolute top-1/2 -right-20 w-32 h-32 rounded-full bg-white/10" />
      </div>

      {/* Right side - form */}
      <div className="w-full lg:w-1/2 xl:w-2/5 flex items-center justify-center p-8">
        <div className="w-full max-w-md animate-fade-in">
          {/* Mobile logo */}
          <div className="lg:hidden text-center mb-8">
            <div
              className="w-16 h-16 rounded-2xl flex items-center justify-center text-4xl mx-auto mb-4"
              style={{ background: "var(--gradient-primary)" }}
            >
              💰
            </div>
            <h1 className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
              Mamoji
            </h1>
          </div>

          <div className="mb-8">
            <h2 className="text-2xl font-bold mb-2" style={{ color: "var(--text-color-1)" }}>
              {t("loginTitle")}
            </h2>
            <p style={{ color: "var(--text-color-3)" }}>
              登录公司工作台继续处理经营数据
            </p>
          </div>

          <Form layout="vertical" onSubmit={handleSubmit} autoComplete="off">
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
              rules={[{ required: true, message: "请输入密码" }]}
            >
              <Input.Password
                prefix={<IconLock style={{ color: "var(--text-color-4)" }} />}
                placeholder={t("password")}
                size="large"
                style={{ height: 48, borderRadius: 12 }}
              />
            </FormItem>

            <div className="flex items-center justify-between mb-6">
              <Checkbox>记住我</Checkbox>
              <a
                href="#"
                className="text-sm hover:underline"
                style={{ color: "var(--color-primary)" }}
              >
                忘记密码？
              </a>
            </div>

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
                }}
              >
                {t("login")}
              </Button>
            </FormItem>
          </Form>

          <div className="text-center mt-6">
            <span style={{ color: "var(--text-color-3)" }}>{t("noAccount")} </span>
            <Link
              href="/register"
              className="font-medium hover:underline"
              style={{ color: "var(--color-primary)" }}
            >
              {t("register")}
            </Link>
          </div>

          {/* Test account hint */}
          <div
            className="mt-8 p-4 rounded-xl text-center text-sm"
            style={{
              backgroundColor: "var(--bg-color-card-hover)",
              color: "var(--text-color-3)",
            }}
          >
            <div className="font-medium mb-1" style={{ color: "var(--text-color-2)" }}>
              测试账号
            </div>
            <div>test@mamoji.com / 123456</div>
          </div>
        </div>
      </div>
    </div>
  );
}
