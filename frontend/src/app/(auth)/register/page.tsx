"use client";

import { Suspense, useMemo, useState } from "react";
import axios from "axios";
import { Alert, Button, Form, Input, Message } from "@arco-design/web-react";
import { IconEmail, IconLock, IconUser } from "@arco-design/web-react/icon";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import AuthShell from "@/components/auth/AuthShell";
import { useAuthStore } from "@/lib/stores/authStore";

const FormItem = Form.Item;

const AVATAR_PRESETS = [
  { emoji: "😊", color: "#335cff" },
  { emoji: "🧑‍💻", color: "#0f766e" },
  { emoji: "🧑‍🚀", color: "#7c3aed" },
  { emoji: "🦊", color: "#9a5268" },
  { emoji: "🐼", color: "#059669" },
  { emoji: "🐯", color: "#ca8a04" },
];

type RegisterValues = {
  nickname: string;
  email: string;
  password: string;
  confirmPassword: string;
};

function passwordClassCount(password: string) {
  return [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((rule) => rule.test(password)).length;
}

function registerErrorMessage(error: unknown, invited: boolean) {
  if (!axios.isAxiosError(error)) return "注册失败，请稍后重试";
  if (!error.response) return "无法连接服务器，请检查网络后重试";
  if (error.response.status === 403) return invited ? "邀请已失效、邮箱不匹配或已被使用" : "当前环境仅允许受邀用户注册";
  if (error.response.status === 409) return "该邮箱已被使用，请直接登录";
  if (error.response.status === 429) return "请求过于频繁，请稍后再试";
  return invited ? "注册失败，请确认邀请链接和邮箱" : "注册失败，请检查填写内容";
}

function RegisterContent() {
  const [loading, setLoading] = useState(false);
  const [selectedAvatar, setSelectedAvatar] = useState(AVATAR_PRESETS[0]);
  const [form] = Form.useForm<RegisterValues>();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { register } = useAuthStore();
  const t = useTranslations("auth");
  const inviteToken = searchParams.get("invite") || searchParams.get("inviteToken") || "";
  const password = Form.useWatch("password", form) || "";
  const passwordStrength = useMemo(() => {
    if (!password) return 0;
    return Math.min(4, passwordClassCount(password) + (password.length >= 12 ? 1 : 0));
  }, [password]);

  const handleSubmit = async (values: RegisterValues) => {
    const { confirmPassword: _confirmPassword, ...payload } = values;
    void _confirmPassword;
    setLoading(true);
    try {
      await register({
        ...payload,
        avatar: `${selectedAvatar.emoji}|${selectedAvatar.color}`,
        inviteToken: inviteToken || undefined,
      });
      Message.success("账号创建成功");
      router.replace("/dashboard");
    } catch (error) {
      Message.error(registerErrorMessage(error, Boolean(inviteToken)));
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthShell
      variant="register"
      title={t("registerTitle")}
      description={inviteToken ? "完成个人信息后加入受邀团队。" : "创建新的经营工作台；生产环境通常需要邀请链接。"}
    >
      {inviteToken ? (
        <Alert className="mb-5" type="success" content="已读取邀请参数，提交时会验证受邀邮箱与有效期。" />
      ) : (
        <Alert className="mb-5" type="info" content="如果生产环境采用邀请制，请从管理员发送的邀请链接进入。" />
      )}

      <Form form={form} layout="vertical" onSubmit={handleSubmit} autoComplete="on">
        <FormItem label="头像">
          <div className="avatar-preset-list" role="group" aria-label="选择头像">
            {AVATAR_PRESETS.map((preset) => {
              const selected = selectedAvatar.emoji === preset.emoji;
              return (
                <button
                  key={preset.emoji}
                  type="button"
                  onClick={() => setSelectedAvatar(preset)}
                  className="avatar-preset"
                  aria-pressed={selected}
                  aria-label={`选择头像 ${preset.emoji}`}
                  style={{
                    backgroundColor: `${preset.color}16`,
                    borderColor: selected ? preset.color : "var(--border-color)",
                    boxShadow: selected ? `0 0 0 3px ${preset.color}18` : "none",
                  }}
                >
                  {preset.emoji}
                </button>
              );
            })}
          </div>
        </FormItem>

        <FormItem label={t("nickname")} field="nickname" rules={[{ required: true, message: "请输入姓名或昵称" }]}>
          <Input prefix={<IconUser aria-hidden="true" />} placeholder="团队中显示的姓名" size="large" autoComplete="name" />
        </FormItem>

        <FormItem
          label={t("email")}
          field="email"
          rules={[
            { required: true, message: "请输入邮箱" },
            { type: "email", message: "请输入有效的邮箱" },
          ]}
        >
          <Input prefix={<IconEmail aria-hidden="true" />} placeholder="name@company.com" size="large" autoComplete="email" />
        </FormItem>

        <FormItem
          label={t("password")}
          field="password"
          rules={[
            { required: true, message: "请输入密码" },
            { minLength: 12, message: "密码至少 12 位" },
            {
              validator: (value, callback) => {
                if (value && passwordClassCount(value) < 3) {
                  callback("请至少组合大写、小写、数字、符号中的三类");
                  return;
                }
                callback();
              },
            },
          ]}
        >
          <Input.Password prefix={<IconLock aria-hidden="true" />} placeholder="至少 12 位，组合三类字符" size="large" autoComplete="new-password" />
        </FormItem>
        <div className="password-strength" aria-label="密码强度">
          {[1, 2, 3, 4].map((level) => <span key={level} data-active={passwordStrength >= level ? "true" : "false"} />)}
        </div>

        <FormItem
          label={t("confirmPassword")}
          field="confirmPassword"
          dependencies={["password"]}
          rules={[
            { required: true, message: "请再次输入密码" },
            {
              validator: (value, callback) => {
                if (value && value !== form.getFieldValue("password")) {
                  callback(t("passwordMismatch"));
                  return;
                }
                callback();
              },
            },
          ]}
        >
          <Input.Password prefix={<IconLock aria-hidden="true" />} placeholder={t("confirmPassword")} size="large" autoComplete="new-password" />
        </FormItem>

        <FormItem className="mb-0">
          <Button type="primary" htmlType="submit" long size="large" loading={loading}>
            {loading ? "正在创建…" : t("register")}
          </Button>
        </FormItem>
      </Form>

      <div className="auth-form-footer">
        <span>{t("hasAccount")} </span>
        <Link href="/login">{t("login")}</Link>
      </div>
    </AuthShell>
  );
}

export default function RegisterPage() {
  return (
    <Suspense fallback={<div className="app-boot-screen"><div className="app-boot-mark">M</div></div>}>
      <RegisterContent />
    </Suspense>
  );
}
