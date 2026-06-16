"use client";
import { useCallback, useEffect, useState } from "react";
import { Button, Dropdown, Avatar, Badge, Tooltip, Input, Empty, Spin, Tag } from "@arco-design/web-react";
import {
  IconMenu,
  IconSun,
  IconMoon,
  IconLanguage,
  IconUser,
  IconPoweroff,
  IconNotification,
  IconSearch,
} from "@arco-design/web-react/icon";
import { useAppStore } from "@/lib/stores/appStore";
import { useAuthStore } from "@/lib/stores/authStore";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import CompanySwitcher from "./CompanySwitcher";
import { notificationApi } from "@/lib/api/notifications";
import type { NotificationItem } from "@/lib/types";

export default function Header() {
  const { toggleSidebar, theme, setTheme, locale, setLocale } = useAppStore();
  const { user, logout } = useAuthStore();
  const router = useRouter();
  const t = useTranslations();
  const [searchVisible, setSearchVisible] = useState(false);
  const [globalSearch, setGlobalSearch] = useState("");
  const [notificationVisible, setNotificationVisible] = useState(false);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [notificationsLoading, setNotificationsLoading] = useState(false);

  const avatarEmoji = user?.avatar?.split("|")[0] || "👤";
  const avatarColor = user?.avatar?.split("|")[1] || "#6366f1";

  const handleLogout = async () => {
    await logout();
    router.push("/login");
  };

  const toggleTheme = () => {
    setTheme(theme === "light" ? "dark" : "light");
  };

  const toggleLocale = () => {
    const next = locale === "zh" ? "en" : "zh";
    setLocale(next);
    window.location.reload();
  };

  const submitGlobalSearch = () => {
    const keyword = globalSearch.trim();
    const params = new URLSearchParams();
    if (keyword) {
      params.set("keyword", keyword);
    }
    router.push(`/transactions${params.toString() ? `?${params.toString()}` : ""}`);
    setSearchVisible(false);
  };

  const loadNotificationSummary = useCallback(async () => {
    try {
      const res = await notificationApi.summary();
      setUnreadCount(res.data.unreadCount || 0);
    } catch {
      setUnreadCount(0);
    }
  }, []);

  const loadNotifications = useCallback(async () => {
    setNotificationsLoading(true);
    try {
      const [listRes, summaryRes] = await Promise.all([
        notificationApi.list({ page: 0, size: 8 }),
        notificationApi.summary(),
      ]);
      setNotifications(listRes.data.content);
      setUnreadCount(summaryRes.data.unreadCount || 0);
    } finally {
      setNotificationsLoading(false);
    }
  }, []);

  useEffect(() => {
    const starter = window.setTimeout(() => {
      void loadNotificationSummary();
    }, 0);
    const timer = window.setInterval(() => {
      void loadNotificationSummary();
    }, 45000);
    return () => {
      window.clearTimeout(starter);
      window.clearInterval(timer);
    };
  }, [loadNotificationSummary]);

  useEffect(() => {
    if (!notificationVisible) {
      return;
    }
    const timer = window.setTimeout(() => {
      void loadNotifications();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [notificationVisible, loadNotifications]);

  const openNotification = async (item: NotificationItem) => {
    if (!item.readAt) {
      await notificationApi.markRead(item.id);
      setUnreadCount((count) => Math.max(0, count - 1));
      setNotifications((current) =>
        current.map((notification) =>
          notification.id === item.id ? { ...notification, readAt: new Date().toISOString() } : notification
        )
      );
    }
    if (item.targetUrl) {
      router.push(item.targetUrl);
      setNotificationVisible(false);
    }
  };

  const markAllNotificationsRead = async () => {
    await notificationApi.markAllRead();
    setUnreadCount(0);
    setNotifications((current) => current.map((item) => ({ ...item, readAt: item.readAt || new Date().toISOString() })));
  };

  const severityColor = (severity: string) => {
    switch (severity) {
      case "critical":
        return "red";
      case "warning":
        return "orange";
      case "success":
        return "green";
      default:
        return "blue";
    }
  };

  const formatTime = (value: string) => {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return date.toLocaleString(locale === "zh" ? "zh-CN" : "en-US", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  };

  const searchDroplist = (
    <div
      className="global-search-panel rounded-xl border p-3 shadow-lg"
      style={{
        width: 360,
        backgroundColor: "var(--bg-color-card)",
        borderColor: "var(--border-color)",
      }}
    >
      <Input
        autoFocus
        prefix={<IconSearch style={{ color: "var(--text-color-4)" }} />}
        placeholder={t("common.globalSearchPlaceholder")}
        value={globalSearch}
        onChange={setGlobalSearch}
        onPressEnter={submitGlobalSearch}
        style={{ height: 40 }}
      />
      <div className="mt-3 flex items-center justify-between gap-3">
        <span className="text-xs" style={{ color: "var(--text-color-3)" }}>
          {t("common.globalSearchHint")}
        </span>
        <Button type="primary" size="small" onClick={submitGlobalSearch}>
          {t("common.search")}
        </Button>
      </div>
    </div>
  );

  const notificationDroplist = (
    <div
      className="notification-panel rounded-xl border shadow-lg"
      style={{
        width: 380,
        maxWidth: "calc(100vw - 24px)",
        backgroundColor: "var(--bg-color-card)",
        borderColor: "var(--border-color)",
      }}
    >
      <div className="flex items-center justify-between gap-3 border-b px-4 py-3" style={{ borderColor: "var(--border-color)" }}>
        <div>
          <div className="text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>
            {t("common.notificationCenter")}
          </div>
          <div className="text-xs" style={{ color: "var(--text-color-3)" }}>
            {t("common.unreadCount", { count: unreadCount })}
          </div>
        </div>
        <Button size="small" type="text" disabled={unreadCount === 0} onClick={markAllNotificationsRead}>
          {t("common.markAllRead")}
        </Button>
      </div>
      <div style={{ maxHeight: 420, overflowY: "auto" }}>
        {notificationsLoading ? (
          <div className="flex justify-center py-8">
            <Spin />
          </div>
        ) : notifications.length === 0 ? (
          <div className="py-8">
            <Empty description={t("common.noNotifications")} />
          </div>
        ) : (
          notifications.map((item) => (
            <button
              key={item.id}
              type="button"
              className="block w-full border-b px-4 py-3 text-left transition-colors hover:bg-black/5 dark:hover:bg-white/5"
              style={{
                borderColor: "var(--border-color)",
                background: item.readAt ? "transparent" : "rgba(22, 93, 255, 0.06)",
              }}
              onClick={() => openNotification(item)}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>
                    {item.title}
                  </div>
                  <div className="mt-1 line-clamp-2 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
                    {item.content}
                  </div>
                </div>
                <Tag color={severityColor(item.severity)} size="small">
                  {t(`common.notificationSeverity.${item.severity}`)}
                </Tag>
              </div>
              <div className="mt-2 text-xs" style={{ color: "var(--text-color-4)" }}>
                {formatTime(item.createdAt)}
              </div>
            </button>
          ))
        )}
      </div>
    </div>
  );

  return (
    <div
      className="flex items-center justify-between px-6 border-b glass"
      style={{
        height: 64,
        borderColor: "var(--border-color)",
      }}
    >
      {/* Left side */}
      <div className="flex items-center gap-4">
        <Button
          type="text"
          icon={<IconMenu />}
          onClick={toggleSidebar}
          className="arco-btn-icon-only"
          style={{ fontSize: 20 }}
        />
      </div>

      {/* Right side */}
      <div className="flex items-center gap-2">
        <CompanySwitcher />

        {/* Search button */}
        <Dropdown
          droplist={searchDroplist}
          trigger="click"
          position="br"
          popupVisible={searchVisible}
          onVisibleChange={setSearchVisible}
        >
          <Button
            type="text"
            icon={<IconSearch />}
            aria-label={t("common.search")}
            className="arco-btn-icon-only"
            style={{ color: "var(--text-color-3)" }}
          />
        </Dropdown>

        <Dropdown
          droplist={notificationDroplist}
          trigger="click"
          position="br"
          popupVisible={notificationVisible}
          onVisibleChange={setNotificationVisible}
        >
          <Tooltip content={t("common.notifications")}>
            <Badge count={unreadCount} maxCount={99}>
            <Button
              type="text"
              icon={<IconNotification />}
              className="arco-btn-icon-only"
              style={{ color: "var(--text-color-3)" }}
            />
            </Badge>
          </Tooltip>
        </Dropdown>

        {/* Theme toggle */}
        <Tooltip content={theme === "light" ? t("settings.switchToDark") : t("settings.switchToLight")}>
          <Button
            type="text"
            icon={theme === "light" ? <IconMoon /> : <IconSun />}
            onClick={toggleTheme}
            className="arco-btn-icon-only"
            style={{ color: "var(--text-color-3)" }}
          />
        </Tooltip>

        {/* Language toggle */}
        <Tooltip content={locale === "zh" ? "English" : "中文"}>
          <Button
            type="text"
            icon={<IconLanguage />}
            onClick={toggleLocale}
            className="arco-btn-icon-only"
            style={{ color: "var(--text-color-3)" }}
          />
        </Tooltip>

        {/* Divider */}
        <div className="w-px h-6 mx-2" style={{ backgroundColor: "var(--border-color)" }} />

        {/* User dropdown */}
        <Dropdown
          droplist={
            <div className="py-1" style={{ minWidth: 160 }}>
              <div className="px-4 py-2 border-b" style={{ borderColor: "var(--border-color)" }}>
                <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                  {user?.nickname || "用户"}
                </div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>
                  {user?.email || ""}
                </div>
              </div>
              <div
                className="flex items-center gap-2 px-4 py-2 cursor-pointer hover:bg-black/5 dark:hover:bg-white/5"
                onClick={() => router.push("/settings")}
              >
                <IconUser style={{ color: "var(--text-color-3)" }} />
                <span style={{ color: "var(--text-color-2)" }}>{t("settings.profile")}</span>
              </div>
              <div
                className="flex items-center gap-2 px-4 py-2 cursor-pointer hover:bg-black/5 dark:hover:bg-white/5 text-red-500"
                onClick={handleLogout}
              >
                <IconPoweroff />
                <span>{t("auth.logout")}</span>
              </div>
            </div>
          }
          trigger="click"
        >
          <div className="flex items-center gap-3 cursor-pointer py-1 px-2 rounded-lg hover:bg-black/5 dark:hover:bg-white/5 transition-colors">
            <Avatar
              size={36}
              style={{
                backgroundColor: avatarColor,
                borderRadius: 10,
              }}
            >
              {avatarEmoji}
            </Avatar>
            <div className="hidden md:block">
              <div className="text-sm font-medium" style={{ color: "var(--text-color-1)" }}>
                {user?.nickname || "用户"}
              </div>
            </div>
          </div>
        </Dropdown>
      </div>
    </div>
  );
}
