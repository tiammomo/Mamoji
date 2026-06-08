"use client";
import { useState } from "react";
import { Card, Form, Input, Button, Message, Tabs } from "@arco-design/web-react";
import { IconArchive, IconLanguage, IconLock, IconMoon, IconSun, IconUser } from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useAuthStore } from "@/lib/stores/authStore";
import { useAppStore } from "@/lib/stores/appStore";
import { authApi } from "@/lib/api/auth";
import PageHeader from "@/components/common/PageHeader";

const FormItem = Form.Item;
const TabPane = Tabs.TabPane;

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

export default function SettingsPage() {
  const t = useTranslations("settings");
  const router = useRouter();
  const { user, updateUser } = useAuthStore();
  const { theme, setTheme, locale, setLocale } = useAppStore();
  const [profileForm] = Form.useForm();
  const [passwordForm] = Form.useForm();
  const [selectedAvatar, setSelectedAvatar] = useState(
    user?.avatar || AVATAR_PRESETS[0].emoji + "|" + AVATAR_PRESETS[0].color
  );

  const handleUpdateProfile = async (values: { nickname: string }) => {
    try {
      const res = await authApi.updateProfile({
        ...values,
        avatar: selectedAvatar,
      });
      updateUser(res.data);
      Message.success(t("profileUpdated"));
    } catch {
      Message.error(t("profileUpdateFailed"));
    }
  };

  const handleChangePassword = async (values: { oldPassword: string; newPassword: string }) => {
    try {
      await authApi.changePassword(values);
      Message.success(t("passwordChanged"));
      passwordForm.resetFields();
    } catch {
      Message.error(t("passwordChangeFailed"));
    }
  };

  const toggleTheme = () => {
    setTheme(theme === "light" ? "dark" : "light");
  };

  const toggleLocale = () => {
    const next = locale === "zh" ? "en" : "zh";
    setLocale(next);
    window.location.reload();
  };

  return (
    <div className="max-w-2xl mx-auto animate-fade-in">
      <PageHeader title={t("title")} icon="⚙️" />

      <Card style={{ borderRadius: 16 }}>
        <Tabs defaultActiveTab="profile">
          <TabPane
            key="profile"
            title={
              <span className="flex items-center gap-2">
                <IconUser /> {t("profile")}
              </span>
            }
          >
            <div className="py-6">
              {/* Avatar selector */}
              <div className="mb-8">
                <label className="block text-sm font-medium mb-3" style={{ color: "var(--text-color-2)" }}>
                  头像
                </label>
                <div className="flex flex-wrap gap-3">
                  {AVATAR_PRESETS.map((preset) => {
                    const val = `${preset.emoji}|${preset.color}`;
                    const isSelected = selectedAvatar === val;
                    return (
                      <button
                        key={preset.emoji}
                        onClick={() => setSelectedAvatar(val)}
                        className="w-14 h-14 rounded-2xl flex items-center justify-center cursor-pointer text-2xl transition-all"
                        style={{
                          backgroundColor: preset.color + "20",
                          border: isSelected ? `3px solid ${preset.color}` : "3px solid transparent",
                          transform: isSelected ? "scale(1.1)" : "scale(1)",
                        }}
                      >
                        {preset.emoji}
                      </button>
                    );
                  })}
                </div>
              </div>

              <Form
                form={profileForm}
                layout="vertical"
                initialValues={{ nickname: user?.nickname }}
                onSubmit={handleUpdateProfile}
              >
                <FormItem label="昵称" field="nickname" rules={[{ required: true }]}>
                  <Input style={{ borderRadius: 12, height: 48 }} />
                </FormItem>
                <FormItem label="邮箱">
                  <Input disabled value={user?.email} style={{ borderRadius: 12, height: 48 }} />
                </FormItem>
                <FormItem>
                  <Button type="primary" htmlType="submit" style={{ borderRadius: 12, height: 44 }}>
                    保存修改
                  </Button>
                </FormItem>
              </Form>
            </div>
          </TabPane>

          <TabPane
            key="password"
            title={
              <span className="flex items-center gap-2">
                <IconLock /> {t("password")}
              </span>
            }
          >
            <div className="py-6">
              <Form form={passwordForm} layout="vertical" onSubmit={handleChangePassword}>
                <FormItem
                  label="旧密码"
                  field="oldPassword"
                  rules={[{ required: true, message: "请输入旧密码" }]}
                >
                  <Input.Password style={{ borderRadius: 12, height: 48 }} />
                </FormItem>
                <FormItem
                  label="新密码"
                  field="newPassword"
                  rules={[
                    { required: true, message: "请输入新密码" },
                    { minLength: 12, message: "密码至少12位" },
                  ]}
                >
                  <Input.Password style={{ borderRadius: 12, height: 48 }} />
                </FormItem>
                <FormItem>
                  <Button type="primary" htmlType="submit" style={{ borderRadius: 12, height: 44 }}>
                    修改密码
                  </Button>
                </FormItem>
              </Form>
            </div>
          </TabPane>

          <TabPane
            key="theme"
            title={
              <span className="flex items-center gap-2">
                {theme === "light" ? <IconSun /> : <IconMoon />} {t("theme")}
              </span>
            }
          >
            <div className="py-6">
              <div className="flex items-center justify-between p-4 rounded-xl" style={{ backgroundColor: "var(--bg-color-page)" }}>
                <div className="flex items-center gap-3">
                  <span className="text-2xl">{theme === "light" ? "☀️" : "🌙"}</span>
                  <div>
                    <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                      {theme === "light" ? t("lightMode") : t("darkMode")}
                    </div>
                    <div className="text-sm" style={{ color: "var(--text-color-3)" }}>
                      {t("currentTheme", { theme: theme === "light" ? t("light") : t("dark") })}
                    </div>
                  </div>
                </div>
                <Button
                  type="outline"
                  icon={theme === "light" ? <IconMoon /> : <IconSun />}
                  onClick={toggleTheme}
                  style={{ borderRadius: 12 }}
                >
                  {t("switch")}
                </Button>
              </div>
            </div>
          </TabPane>

          <TabPane
            key="language"
            title={
              <span className="flex items-center gap-2">
                <IconLanguage /> {t("language")}
              </span>
            }
          >
            <div className="py-6">
              <div className="flex items-center justify-between p-4 rounded-xl" style={{ backgroundColor: "var(--bg-color-page)" }}>
                <div className="flex items-center gap-3">
                  <span className="text-2xl">{locale === "zh" ? "🇨🇳" : "🇺🇸"}</span>
                  <div>
                    <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                      {locale === "zh" ? t("languageChinese") : t("languageEnglish")}
                    </div>
                    <div className="text-sm" style={{ color: "var(--text-color-3)" }}>
                      {t("currentLanguage", { language: locale === "zh" ? t("languageChinese") : t("languageEnglish") })}
                    </div>
                  </div>
                </div>
                <Button
                  type="outline"
                  onClick={toggleLocale}
                  style={{ borderRadius: 12 }}
                >
                  {t("switchToLanguage", { language: locale === "zh" ? t("languageEnglish") : t("languageChinese") })}
                </Button>
              </div>
            </div>
          </TabPane>

          <TabPane
            key="tools"
            title={
              <span className="flex items-center gap-2">
                <IconArchive /> 数据与管理
              </span>
            }
          >
            <div className="py-6 space-y-4">
              <div className="flex items-center justify-between p-4 rounded-xl" style={{ backgroundColor: "var(--bg-color-page)" }}>
                <div className="flex items-center gap-3">
                  <span className="text-2xl">💾</span>
                  <div>
                    <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                      经营数据备份
                    </div>
                    <div className="text-sm" style={{ color: "var(--text-color-3)" }}>
                      导出当前公司经营数据，或校验备份文件。
                    </div>
                  </div>
                </div>
                <Button type="outline" onClick={() => router.push("/backup")} style={{ borderRadius: 12 }}>
                  打开
                </Button>
              </div>

              {user?.role === 1 && (
                <div className="flex items-center justify-between p-4 rounded-xl" style={{ backgroundColor: "var(--bg-color-page)" }}>
                  <div className="flex items-center gap-3">
                    <span className="text-2xl">👥</span>
                    <div>
                      <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                        人员信息
                      </div>
                      <div className="text-sm" style={{ color: "var(--text-color-3)" }}>
                        管理团队成员、角色和权限，仅管理员可见。
                      </div>
                    </div>
                  </div>
                  <Button type="outline" icon={<IconUser />} onClick={() => router.push("/admin/users")} style={{ borderRadius: 12 }}>
                    打开
                  </Button>
                </div>
              )}
            </div>
          </TabPane>
        </Tabs>
      </Card>
    </div>
  );
}
