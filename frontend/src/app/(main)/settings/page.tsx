"use client";
import { useEffect, useState } from "react";
import { Button, Card, Checkbox, Form, Input, Message, Select, Switch, Tabs } from "@arco-design/web-react";
import { IconArchive, IconLanguage, IconLock, IconMoon, IconNotification, IconSun, IconUser } from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useAuthStore } from "@/lib/stores/authStore";
import { useAppStore } from "@/lib/stores/appStore";
import { authApi } from "@/lib/api/auth";
import { notificationApi } from "@/lib/api/notifications";
import type { NotificationPreference } from "@/lib/types";
import PageHeader from "@/components/common/PageHeader";

const FormItem = Form.Item;
const TabPane = Tabs.TabPane;
const CheckboxGroup = Checkbox.Group;

const AVATAR_PRESETS = [
  { emoji: "😊", color: "#6366f1" },
  { emoji: "🐱", color: "#ec4899" },
  { emoji: "🐶", color: "#b86a52" },
  { emoji: "🐼", color: "#10b981" },
  { emoji: "🦊", color: "#9a5268" },
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
  const [notificationPreference, setNotificationPreference] = useState<NotificationPreference | null>(null);
  const [notificationSaving, setNotificationSaving] = useState(false);
  const [webhookTesting, setWebhookTesting] = useState(false);
  const [selectedAvatar, setSelectedAvatar] = useState(
    user?.avatar || AVATAR_PRESETS[0].emoji + "|" + AVATAR_PRESETS[0].color
  );

  const notificationTypeOptions = [
    { label: t("notificationTypeTax"), value: "tax" },
    { label: t("notificationTypeReceipt"), value: "receipt" },
    { label: t("notificationTypePayroll"), value: "payroll" },
    { label: t("notificationTypePeople"), value: "people" },
    { label: t("notificationTypeFinance"), value: "finance" },
    { label: t("notificationTypeSystem"), value: "system" },
  ];

  useEffect(() => {
    const timer = window.setTimeout(async () => {
      try {
        const res = await notificationApi.preference();
        setNotificationPreference(res.data);
      } catch {
        Message.error(t("notificationLoadFailed"));
      }
    }, 0);
    return () => window.clearTimeout(timer);
  }, [t]);

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

  const updateNotificationPreference = <K extends keyof NotificationPreference>(key: K, value: NotificationPreference[K]) => {
    setNotificationPreference((current) => current ? { ...current, [key]: value } : current);
  };

  const saveNotificationPreference = async () => {
    if (!notificationPreference) {
      return;
    }
    setNotificationSaving(true);
    try {
      const res = await notificationApi.updatePreference({
        enabled: notificationPreference.enabled,
        webhookEnabled: notificationPreference.webhookEnabled,
        webhookProvider: notificationPreference.webhookProvider,
        webhookUrl: notificationPreference.webhookUrl || "",
        minSeverity: notificationPreference.minSeverity,
        mutedTypes: notificationPreference.mutedTypes,
      });
      setNotificationPreference(res.data);
      Message.success(t("notificationSaved"));
    } catch {
      Message.error(t("notificationSaveFailed"));
    } finally {
      setNotificationSaving(false);
    }
  };

  const testWebhook = async () => {
    if (!notificationPreference) {
      return;
    }
    setWebhookTesting(true);
    try {
      const res = await notificationApi.updatePreference({
        enabled: notificationPreference.enabled,
        webhookEnabled: notificationPreference.webhookEnabled,
        webhookProvider: notificationPreference.webhookProvider,
        webhookUrl: notificationPreference.webhookUrl || "",
        minSeverity: notificationPreference.minSeverity,
        mutedTypes: notificationPreference.mutedTypes,
      });
      setNotificationPreference(res.data);
      await notificationApi.testWebhook();
      Message.success(t("webhookTestSent"));
    } catch {
      Message.error(t("webhookTestFailed"));
    } finally {
      setWebhookTesting(false);
    }
  };

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader title={t("title")} subtitle="管理个人资料、安全、显示偏好、通知渠道与系统入口" icon="⚙️" />

      <Card className="settings-workspace" style={{ borderRadius: 16 }}>
        <Tabs defaultActiveTab="profile">
          <TabPane
            key="profile"
            title={
              <span className="flex items-center gap-2">
                <IconUser /> {t("profile")}
              </span>
            }
          >
            <div className="settings-profile-grid py-6">
              <section className="min-w-0">
                <div className="mb-7">
                  <label className="mb-3 block text-sm font-medium" style={{ color: "var(--text-color-2)" }}>
                    选择头像
                  </label>
                  <div className="flex flex-wrap gap-3">
                    {AVATAR_PRESETS.map((preset) => {
                      const val = `${preset.emoji}|${preset.color}`;
                      const isSelected = selectedAvatar === val;
                      return (
                        <button
                          key={preset.emoji}
                          type="button"
                          aria-label={`选择 ${preset.emoji} 头像`}
                          aria-pressed={isSelected}
                          onClick={() => setSelectedAvatar(val)}
                          className="avatar-preset flex h-14 w-14 cursor-pointer items-center justify-center rounded-2xl text-2xl transition-all"
                          style={{
                            backgroundColor: preset.color + "20",
                            border: isSelected ? `3px solid ${preset.color}` : "3px solid transparent",
                            transform: isSelected ? "scale(1.06)" : "scale(1)",
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
                  <FormItem label="登录邮箱">
                    <Input disabled value={user?.email} style={{ borderRadius: 12, height: 48 }} />
                  </FormItem>
                  <FormItem>
                    <Button type="primary" htmlType="submit" style={{ borderRadius: 12, height: 44 }}>
                      保存个人资料
                    </Button>
                  </FormItem>
                </Form>
              </section>

              <aside className="settings-aside-panel">
                <div className="flex items-center gap-3">
                  <span className="grid h-12 w-12 place-items-center rounded-2xl text-xl" style={{ background: "var(--gradient-primary)", color: "#fff" }}>
                    <IconUser />
                  </span>
                  <div className="min-w-0">
                    <div className="truncate font-semibold" style={{ color: "var(--text-color-1)" }}>{user?.nickname || "Mamoji 用户"}</div>
                    <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>{user?.email}</div>
                  </div>
                </div>
                <div className="my-5 grid grid-cols-2 gap-3">
                  <div className="rounded-2xl bg-white/70 p-3 dark:bg-white/[0.04]">
                    <div className="text-xs" style={{ color: "var(--text-color-3)" }}>账户角色</div>
                    <div className="mt-1 font-semibold">{user?.role === 1 ? "管理员" : "团队成员"}</div>
                  </div>
                  <div className="rounded-2xl bg-white/70 p-3 dark:bg-white/[0.04]">
                    <div className="text-xs" style={{ color: "var(--text-color-3)" }}>当前主题</div>
                    <div className="mt-1 font-semibold">{theme === "light" ? "浅色" : "深色"}</div>
                  </div>
                </div>
                <div className="space-y-2.5">
                  <button type="button" className="settings-aside-action" onClick={() => router.push("/backup")}>
                    <IconArchive className="text-lg" style={{ color: "var(--color-primary)" }} />
                    <span><span className="block font-medium">经营数据备份</span><span className="block text-xs" style={{ color: "var(--text-color-3)" }}>导出、校验与受控恢复</span></span>
                  </button>
                  {user?.role === 1 ? (
                    <button type="button" className="settings-aside-action" onClick={() => router.push("/admin/users")}>
                      <IconUser className="text-lg" style={{ color: "var(--color-primary)" }} />
                      <span><span className="block font-medium">人员与权限</span><span className="block text-xs" style={{ color: "var(--text-color-3)" }}>维护成员资料和访问范围</span></span>
                    </button>
                  ) : null}
                  <button type="button" className="settings-aside-action" onClick={toggleTheme}>
                    {theme === "light" ? <IconMoon className="text-lg" /> : <IconSun className="text-lg" />}
                    <span><span className="block font-medium">切换显示主题</span><span className="block text-xs" style={{ color: "var(--text-color-3)" }}>立即应用到整个工作台</span></span>
                  </button>
                </div>
              </aside>
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
            key="notifications"
            title={
              <span className="flex items-center gap-2">
                <IconNotification /> {t("notifications")}
              </span>
            }
          >
            <div className="py-6 space-y-5">
              <div className="flex items-center justify-between gap-4 p-4 rounded-xl" style={{ backgroundColor: "var(--bg-color-page)" }}>
                <div>
                  <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                    {t("notificationMasterSwitch")}
                  </div>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>
                    {t("notificationMasterSwitchHint")}
                  </div>
                </div>
                <Switch
                  checked={notificationPreference?.enabled ?? true}
                  onChange={(checked) => updateNotificationPreference("enabled", checked)}
                  disabled={!notificationPreference}
                />
              </div>

              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <label className="block text-sm font-medium mb-2" style={{ color: "var(--text-color-2)" }}>
                    {t("minimumSeverity")}
                  </label>
                  <Select
                    value={notificationPreference?.minSeverity || "info"}
                    onChange={(value) => updateNotificationPreference("minSeverity", String(value))}
                    style={{ width: "100%" }}
                    disabled={!notificationPreference}
                  >
                    <Select.Option value="info">{t("severityInfo")}</Select.Option>
                    <Select.Option value="warning">{t("severityWarning")}</Select.Option>
                    <Select.Option value="critical">{t("severityCritical")}</Select.Option>
                  </Select>
                </div>
                <div>
                  <label className="block text-sm font-medium mb-2" style={{ color: "var(--text-color-2)" }}>
                    {t("webhookProvider")}
                  </label>
                  <Select
                    value={notificationPreference?.webhookProvider || "generic"}
                    onChange={(value) => updateNotificationPreference("webhookProvider", String(value))}
                    style={{ width: "100%" }}
                    disabled={!notificationPreference}
                  >
                    <Select.Option value="generic">{t("webhookProviderGeneric")}</Select.Option>
                    <Select.Option value="feishu">{t("webhookProviderFeishu")}</Select.Option>
                    <Select.Option value="wecom">{t("webhookProviderWecom")}</Select.Option>
                  </Select>
                </div>
              </div>

              <div>
                <label className="block text-sm font-medium mb-2" style={{ color: "var(--text-color-2)" }}>
                  {t("mutedTypes")}
                </label>
                <CheckboxGroup
                  value={notificationPreference?.mutedTypes || []}
                  options={notificationTypeOptions}
                  onChange={(values) => updateNotificationPreference("mutedTypes", values.map(String))}
                  disabled={!notificationPreference}
                />
              </div>

              <div className="space-y-3 p-4 rounded-xl" style={{ backgroundColor: "var(--bg-color-page)" }}>
                <div className="flex items-center justify-between gap-4">
                  <div>
                    <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                      {t("webhookDelivery")}
                    </div>
                    <div className="text-sm" style={{ color: "var(--text-color-3)" }}>
                      {t("webhookDeliveryHint")}
                    </div>
                  </div>
                  <Switch
                    checked={notificationPreference?.webhookEnabled ?? false}
                    onChange={(checked) => updateNotificationPreference("webhookEnabled", checked)}
                    disabled={!notificationPreference}
                  />
                </div>
                <Input
                  value={notificationPreference?.webhookUrl || ""}
                  onChange={(value) => updateNotificationPreference("webhookUrl", value)}
                  placeholder={t("webhookUrlPlaceholder")}
                  disabled={!notificationPreference || !notificationPreference.webhookEnabled}
                />
                <div className="flex justify-end gap-2">
                  <Button
                    type="outline"
                    onClick={testWebhook}
                    loading={webhookTesting}
                    disabled={!notificationPreference?.webhookEnabled || !notificationPreference?.webhookUrl}
                  >
                    {t("testWebhook")}
                  </Button>
                  <Button type="primary" onClick={saveNotificationPreference} loading={notificationSaving} disabled={!notificationPreference}>
                    {t("saveNotificationSettings")}
                  </Button>
                </div>
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
