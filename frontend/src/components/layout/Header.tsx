"use client";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, Dropdown, Avatar, Badge, Tooltip, Input, Empty, Message, Spin, Tag } from "@arco-design/web-react";
import {
  IconMenu,
  IconSun,
  IconMoon,
  IconLanguage,
  IconUser,
  IconPoweroff,
  IconNotification,
  IconSearch,
  IconRight,
  IconPlus,
} from "@arco-design/web-react/icon";
import { useAppStore } from "@/lib/stores/appStore";
import { useAuthStore } from "@/lib/stores/authStore";
import { usePathname, useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import CompanySwitcher from "./CompanySwitcher";
import { notificationApi } from "@/lib/api/notifications";
import { globalSearchApi, type GlobalSearchResult } from "@/lib/api/search";
import type { NotificationItem } from "@/lib/types";
import { activeNavigationItem, flattenNavigation, navigationFor } from "./navigation";

export default function Header() {
  const { toggleSidebar, theme, setTheme, locale, setLocale, activeCompanyId, activeSubjectType } = useAppStore();
  const { user, logout } = useAuthStore();
  const router = useRouter();
  const pathname = usePathname();
  const t = useTranslations();
  const [searchVisible, setSearchVisible] = useState(false);
  const [globalSearch, setGlobalSearch] = useState("");
  const [notificationVisible, setNotificationVisible] = useState(false);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [notificationsLoading, setNotificationsLoading] = useState(false);
  const [notificationsFailed, setNotificationsFailed] = useState(false);
  const [businessSearchState, setBusinessSearchState] = useState<{
    requestKey: string;
    results: GlobalSearchResult[];
    loading: boolean;
  }>({ requestKey: "", results: [], loading: false });
  const [loggingOut, setLoggingOut] = useState(false);
  const [notificationActionId, setNotificationActionId] = useState<number | null>(null);
  const [markingAllNotificationsRead, setMarkingAllNotificationsRead] = useState(false);
  const logoutPendingRef = useRef(false);
  const notificationDataRevisionRef = useRef(0);
  const notificationActionIdsRef = useRef(new Set<number>());
  const markAllNotificationsPendingRef = useRef(false);
  const notificationListControllerRef = useRef<AbortController | null>(null);
  const notificationSummaryRequestRef = useRef<{
    controller: AbortController;
    promise: Promise<void>;
  } | null>(null);
  const accessContext = useAuthStore((state) => state.accessContext);
  const navigationGroups = useMemo(
    () => navigationFor(activeSubjectType, {
      isAdmin: user?.role === 1,
      permissions: accessContext?.permissions,
      modules: accessContext?.modules.enabled,
    }),
    [accessContext?.modules.enabled, accessContext?.permissions, activeSubjectType, user?.role]
  );
  const navigationItems = useMemo(() => flattenNavigation(navigationGroups), [navigationGroups]);
  const currentNavigationItem = activeNavigationItem(pathname, navigationItems);
  const normalizedSearch = globalSearch.trim().toLocaleLowerCase();
  const businessSearchRequestKey = `${activeCompanyId ?? "unscoped"}:${globalSearch.trim()}`;
  const businessSearchResults = businessSearchState.requestKey === businessSearchRequestKey
    ? businessSearchState.results
    : [];
  const businessSearchLoading = businessSearchState.requestKey === businessSearchRequestKey
    && businessSearchState.loading;
  const searchResults = navigationItems.filter((item) => {
    if (!normalizedSearch) return true;
    const label = t(`nav.${item.labelKey}`).toLocaleLowerCase();
    return label.includes(normalizedSearch)
      || item.key.toLocaleLowerCase().includes(normalizedSearch)
      || item.keywords.some((keyword) => keyword.toLocaleLowerCase().includes(normalizedSearch));
  });

  const avatarEmoji = user?.avatar?.split("|")[0] || "👤";
  const avatarColor = user?.avatar?.split("|")[1] || "#6366f1";

  const handleLogout = async () => {
    if (logoutPendingRef.current) return;
    logoutPendingRef.current = true;
    setLoggingOut(true);
    try {
      await logout();
      router.replace("/login");
    } finally {
      logoutPendingRef.current = false;
      setLoggingOut(false);
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

  const submitGlobalSearch = () => {
    const keyword = globalSearch.trim();
    if (!keyword) {
      return;
    }
    const params = new URLSearchParams();
    params.set("keyword", keyword);
    router.push(`/transactions${params.toString() ? `?${params.toString()}` : ""}`);
    setSearchVisible(false);
  };

  const openNavigationResult = (path: string) => {
    router.push(path);
    setSearchVisible(false);
    setGlobalSearch("");
  };

  const loadNotificationSummary = useCallback(() => {
    if (markAllNotificationsPendingRef.current || notificationActionIdsRef.current.size > 0) {
      return Promise.resolve();
    }
    const pending = notificationSummaryRequestRef.current;
    if (pending) return pending.promise;

    const controller = new AbortController();
    const promise = (async () => {
      try {
        const res = await notificationApi.summary(controller.signal);
        if (!controller.signal.aborted) {
          setUnreadCount(res.data.unreadCount || 0);
        }
      } catch {
        // Keep the last known count on transient polling failures.
      }
    })();
    notificationSummaryRequestRef.current = { controller, promise };
    const release = () => {
      if (notificationSummaryRequestRef.current?.promise === promise) {
        notificationSummaryRequestRef.current = null;
      }
    };
    void promise.then(release, release);
    return promise;
  }, []);

  const loadNotifications = useCallback(async () => {
    if (markAllNotificationsPendingRef.current || notificationActionIdsRef.current.size > 0) return;
    notificationListControllerRef.current?.abort();
    const controller = new AbortController();
    const revision = notificationDataRevisionRef.current;
    notificationListControllerRef.current = controller;
    setNotificationsLoading(true);
    setNotificationsFailed(false);
    try {
      const [listRes] = await Promise.all([
        notificationApi.list({ page: 0, size: 8 }, controller.signal),
        loadNotificationSummary(),
      ]);
      if (!controller.signal.aborted && revision === notificationDataRevisionRef.current) {
        setNotifications(listRes.data.content);
      }
    } catch {
      if (!controller.signal.aborted && revision === notificationDataRevisionRef.current) {
        setNotificationsFailed(true);
      }
    } finally {
      if (notificationListControllerRef.current === controller) {
        notificationListControllerRef.current = null;
        setNotificationsLoading(false);
      }
    }
  }, [loadNotificationSummary]);

  useEffect(() => {
    const refreshWhenAvailable = () => {
      if (document.visibilityState !== "hidden" && navigator.onLine) {
        void loadNotificationSummary();
      }
    };
    const starter = window.setTimeout(() => {
      refreshWhenAvailable();
    }, 0);
    const timer = window.setInterval(() => {
      refreshWhenAvailable();
    }, 45000);
    document.addEventListener("visibilitychange", refreshWhenAvailable);
    window.addEventListener("online", refreshWhenAvailable);
    return () => {
      window.clearTimeout(starter);
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", refreshWhenAvailable);
      window.removeEventListener("online", refreshWhenAvailable);
      notificationSummaryRequestRef.current?.controller.abort();
      notificationSummaryRequestRef.current = null;
    };
  }, [loadNotificationSummary]);

  useEffect(() => {
    if (!notificationVisible) {
      notificationListControllerRef.current?.abort();
      return;
    }
    const timer = window.setTimeout(() => {
      void loadNotifications();
    }, 0);
    return () => {
      window.clearTimeout(timer);
      notificationListControllerRef.current?.abort();
    };
  }, [notificationVisible, loadNotifications]);

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLocaleLowerCase() === "k") {
        event.preventDefault();
        setSearchVisible((visible) => !visible);
      }
    };
    window.addEventListener("keydown", handleShortcut);
    return () => window.removeEventListener("keydown", handleShortcut);
  }, []);

  useEffect(() => {
    if (!searchVisible || !activeCompanyId || normalizedSearch.length < 2) return;

    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setBusinessSearchState({ requestKey: businessSearchRequestKey, results: [], loading: true });
      try {
        const response = await globalSearchApi.search(globalSearch.trim(), 5, controller.signal);
        if (!controller.signal.aborted) {
          setBusinessSearchState({
            requestKey: businessSearchRequestKey,
            results: response.data.results,
            loading: false,
          });
        }
      } catch {
        if (!controller.signal.aborted) {
          setBusinessSearchState({ requestKey: businessSearchRequestKey, results: [], loading: false });
        }
      }
    }, 260);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [activeCompanyId, businessSearchRequestKey, globalSearch, normalizedSearch, searchVisible]);

  const openNotification = async (item: NotificationItem) => {
    if (markAllNotificationsPendingRef.current || notificationActionIdsRef.current.size > 0) return;
    notificationActionIdsRef.current.add(item.id);
    notificationDataRevisionRef.current += 1;
    notificationSummaryRequestRef.current?.controller.abort();
    notificationSummaryRequestRef.current = null;
    setNotificationActionId(item.id);
    try {
      if (!item.readAt) {
        await notificationApi.markRead(item.id);
        setUnreadCount((count) => Math.max(0, count - 1));
        setNotifications((current) =>
          current.map((notification) =>
            notification.id === item.id ? { ...notification, readAt: new Date().toISOString() } : notification
          )
        );
      }
      if (item.targetUrl?.startsWith("/") && !item.targetUrl.startsWith("//")) {
        router.push(item.targetUrl);
        setNotificationVisible(false);
      }
    } catch {
      Message.error(t("common.notificationActionFailed"));
    } finally {
      notificationActionIdsRef.current.delete(item.id);
      setNotificationActionId((current) => current === item.id ? null : current);
    }
  };

  const markAllNotificationsRead = async () => {
    if (markAllNotificationsPendingRef.current || notificationActionIdsRef.current.size > 0) return;
    markAllNotificationsPendingRef.current = true;
    notificationDataRevisionRef.current += 1;
    notificationSummaryRequestRef.current?.controller.abort();
    notificationSummaryRequestRef.current = null;
    setMarkingAllNotificationsRead(true);
    try {
      await notificationApi.markAllRead();
      setUnreadCount(0);
      setNotifications((current) => current.map((item) => ({ ...item, readAt: item.readAt || new Date().toISOString() })));
    } catch {
      Message.error(t("common.notificationActionFailed"));
    } finally {
      markAllNotificationsPendingRef.current = false;
      setMarkingAllNotificationsRead(false);
    }
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
      className="global-search-panel overflow-hidden rounded-2xl border shadow-xl"
      style={{
        width: 520,
        maxWidth: "calc(100vw - 24px)",
        backgroundColor: "var(--bg-color-card)",
        borderColor: "var(--border-color)",
      }}
    >
      <div className="border-b p-3" style={{ borderColor: "var(--border-color)" }}>
        <Input
          autoFocus
          allowClear
          prefix={<IconSearch style={{ color: "var(--text-color-4)" }} />}
          placeholder={t("common.globalSearchPlaceholder")}
          value={globalSearch}
          onChange={setGlobalSearch}
          onPressEnter={submitGlobalSearch}
          style={{ height: 44 }}
        />
      </div>
      <div className="max-h-[380px] overflow-y-auto p-2">
        {normalizedSearch.length >= 2 && (
          <>
            <div className="flex items-center justify-between px-2 pb-2 pt-1 text-xs font-semibold uppercase tracking-[0.12em]" style={{ color: "var(--text-color-3)" }}>
              <span>{locale === "zh" ? "业务结果" : "Business results"}</span>
              {businessSearchLoading && <Spin size={14} />}
            </div>
            {!businessSearchLoading && businessSearchResults.length === 0 ? (
              <div className="px-3 py-3 text-sm" style={{ color: "var(--text-color-3)" }}>
                {locale === "zh" ? "没有找到相关流水、票据、账户、税务或员工记录" : "No matching business records"}
              </div>
            ) : (
              businessSearchResults.slice(0, 10).map((result) => (
                <button
                  key={`${result.type}-${result.id}`}
                  type="button"
                  className="search-result-item"
                  onClick={() => openNavigationResult(result.path)}
                >
                  <span className="search-result-icon search-result-icon-primary" aria-hidden="true"><IconSearch /></span>
                  <span className="min-w-0 flex-1 text-left">
                    <span className="block truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{result.title}</span>
                    <span className="block truncate text-xs" style={{ color: "var(--text-color-3)" }}>{result.subtitle}</span>
                  </span>
                  <Tag size="small">{result.type}</Tag>
                  <IconRight style={{ color: "var(--text-color-4)" }} />
                </button>
              ))
            )}
            <div className="my-2 border-t" style={{ borderColor: "var(--border-color-light)" }} />
          </>
        )}
        <div className="px-2 pb-2 pt-1 text-xs font-semibold uppercase tracking-[0.12em]" style={{ color: "var(--text-color-3)" }}>
          {t("common.navigationResults")}
        </div>
        {searchResults.length === 0 ? (
          <div className="px-3 py-6 text-center text-sm" style={{ color: "var(--text-color-3)" }}>
            {t("common.noMatches")}
          </div>
        ) : (
          searchResults.slice(0, 7).map((item) => (
            <button
              key={item.key}
              type="button"
              className="search-result-item"
              onClick={() => openNavigationResult(item.key)}
            >
              <span className="search-result-icon" aria-hidden="true">{item.icon}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>
                  {t(`nav.${item.labelKey}`)}
                </span>
                <span className="block truncate text-xs" style={{ color: "var(--text-color-3)" }}>{item.key}</span>
              </span>
              <IconRight style={{ color: "var(--text-color-4)" }} />
            </button>
          ))
        )}
        <div className="my-2 border-t" style={{ borderColor: "var(--border-color-light)" }} />
        <button
          type="button"
          className="search-result-item"
          onClick={() => openNavigationResult("/transactions?action=new")}
        >
          <span className="search-result-icon search-result-icon-primary" aria-hidden="true"><IconPlus /></span>
          <span className="min-w-0 flex-1 text-left text-sm font-medium" style={{ color: "var(--text-color-1)" }}>
            {t("common.quickCreateTransaction")}
          </span>
          <IconRight style={{ color: "var(--text-color-4)" }} />
        </button>
        {normalizedSearch && (
          <button type="button" className="search-result-item" onClick={submitGlobalSearch}>
            <span className="search-result-icon" aria-hidden="true"><IconSearch /></span>
            <span className="min-w-0 flex-1 text-left">
              <span className="block truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>
                {t("common.searchLedgerFor", { keyword: globalSearch.trim() })}
              </span>
              <span className="block text-xs" style={{ color: "var(--text-color-3)" }}>{t("common.globalSearchHint")}</span>
            </span>
            <IconRight style={{ color: "var(--text-color-4)" }} />
          </button>
        )}
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
        <Button
          size="small"
          type="text"
          loading={markingAllNotificationsRead}
          disabled={unreadCount === 0 || notificationActionId !== null}
          onClick={markAllNotificationsRead}
        >
          {t("common.markAllRead")}
        </Button>
      </div>
      <div style={{ maxHeight: 420, overflowY: "auto" }}>
        {notificationsLoading ? (
          <div className="flex justify-center py-8">
            <Spin />
          </div>
        ) : notificationsFailed && notifications.length === 0 ? (
          <div className="flex flex-col items-center gap-3 px-5 py-8 text-center">
            <div className="text-sm" style={{ color: "var(--text-color-3)" }}>{t("common.notificationLoadFailed")}</div>
            <Button size="small" onClick={() => void loadNotifications()}>{t("common.retry")}</Button>
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
              disabled={notificationActionId !== null || markingAllNotificationsRead}
              aria-busy={notificationActionId === item.id}
              className="block w-full border-b px-4 py-3 text-left transition-colors hover:bg-black/5 disabled:cursor-wait disabled:opacity-60 dark:hover:bg-white/5"
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
    <header
      className="app-header glass"
      style={{
        borderColor: "var(--border-color)",
      }}
    >
      <div className="flex min-w-0 items-center gap-3">
        <Button
          type="text"
          icon={<IconMenu />}
          onClick={toggleSidebar}
          aria-label={t("nav.collapseMenu")}
          className="arco-btn-icon-only hidden md:inline-flex"
          style={{ fontSize: 20 }}
        />
        <button
          type="button"
          className="app-header-mobile-logo md:hidden"
          onClick={() => router.push("/dashboard")}
          aria-label="Mamoji"
        >
          M
        </button>
        <div className="hidden min-w-0 md:block">
          <div className="truncate text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>
            {currentNavigationItem ? t(`nav.${currentNavigationItem.labelKey}`) : "Mamoji"}
          </div>
          <div className="truncate text-xs" style={{ color: "var(--text-color-3)" }}>
            {activeSubjectType === "household" ? t("companySwitcher.householdSubject") : t("companySwitcher.companySubject")}
          </div>
        </div>
      </div>

      <div className="flex min-w-0 items-center gap-1.5 sm:gap-2">
        <CompanySwitcher />

        <Dropdown
          droplist={searchDroplist}
          trigger="click"
          position="br"
          popupVisible={searchVisible}
          onVisibleChange={setSearchVisible}
        >
          <button
            type="button"
            aria-label={t("common.search")}
            className="header-search-trigger"
          >
            <IconSearch />
            <span className="hidden xl:inline">{t("common.search")}</span>
            <kbd className="hidden xl:inline-flex">⌘ K</kbd>
          </button>
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
              aria-label={t("common.notifications")}
              className="arco-btn-icon-only"
              style={{ color: "var(--text-color-3)" }}
            />
            </Badge>
          </Tooltip>
        </Dropdown>

        <div className="hidden items-center gap-1 lg:flex">
          <Tooltip content={theme === "light" ? t("settings.switchToDark") : t("settings.switchToLight")}>
            <Button
              type="text"
              icon={theme === "light" ? <IconMoon /> : <IconSun />}
              onClick={toggleTheme}
              aria-label={theme === "light" ? t("settings.switchToDark") : t("settings.switchToLight")}
              className="arco-btn-icon-only"
              style={{ color: "var(--text-color-3)" }}
            />
          </Tooltip>
          <Tooltip content={locale === "zh" ? "English" : "中文"}>
            <Button
              type="text"
              icon={<IconLanguage />}
              onClick={toggleLocale}
              aria-label={locale === "zh" ? "English" : "中文"}
              className="arco-btn-icon-only"
              style={{ color: "var(--text-color-3)" }}
            />
          </Tooltip>
          <div className="mx-1 h-6 w-px" style={{ backgroundColor: "var(--border-color)" }} />
        </div>

        {/* User dropdown */}
        <Dropdown
          droplist={
            <div className="py-1" style={{ minWidth: 220 }}>
              <div className="px-4 py-2 border-b" style={{ borderColor: "var(--border-color)" }}>
                <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                  {user?.nickname || "用户"}
                </div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>
                  {user?.email || ""}
                </div>
              </div>
              <button
                type="button"
                className="flex w-full cursor-pointer items-center gap-2 border-0 bg-transparent px-4 py-2 text-left hover:bg-black/5 dark:hover:bg-white/5"
                onClick={() => router.push("/settings")}
              >
                <IconUser style={{ color: "var(--text-color-3)" }} />
                <span style={{ color: "var(--text-color-2)" }}>{t("settings.profile")}</span>
              </button>
              <button
                type="button"
                className="flex w-full cursor-pointer items-center gap-2 border-0 bg-transparent px-4 py-2 text-left hover:bg-black/5 dark:hover:bg-white/5 lg:hidden"
                onClick={toggleTheme}
              >
                {theme === "light" ? <IconMoon /> : <IconSun />}
                <span style={{ color: "var(--text-color-2)" }}>
                  {theme === "light" ? t("settings.switchToDark") : t("settings.switchToLight")}
                </span>
              </button>
              <button
                type="button"
                className="flex w-full cursor-pointer items-center gap-2 border-0 bg-transparent px-4 py-2 text-left hover:bg-black/5 dark:hover:bg-white/5 lg:hidden"
                onClick={toggleLocale}
              >
                <IconLanguage />
                <span style={{ color: "var(--text-color-2)" }}>{locale === "zh" ? "English" : "中文"}</span>
              </button>
              <button
                type="button"
                disabled={loggingOut}
                aria-busy={loggingOut}
                className="flex w-full cursor-pointer items-center gap-2 border-0 bg-transparent px-4 py-2 text-left text-red-500 hover:bg-black/5 disabled:cursor-not-allowed disabled:opacity-60 dark:hover:bg-white/5"
                onClick={handleLogout}
              >
                {loggingOut ? <Spin size={14} /> : <IconPoweroff />}
                <span>{t("auth.logout")}</span>
              </button>
            </div>
          }
          trigger="click"
        >
          <button
            type="button"
            className="flex cursor-pointer items-center gap-3 rounded-xl border-0 bg-transparent p-1 transition-colors hover:bg-black/5 dark:hover:bg-white/5"
            aria-label={user?.nickname || t("nav.userFallback")}
          >
            <Avatar
              size={36}
              style={{
                backgroundColor: avatarColor,
                borderRadius: 10,
              }}
            >
              {avatarEmoji}
            </Avatar>
            <div className="hidden xl:block">
              <div className="text-sm font-medium" style={{ color: "var(--text-color-1)" }}>
                {user?.nickname || "用户"}
              </div>
            </div>
          </button>
        </Dropdown>
      </div>
    </header>
  );
}
