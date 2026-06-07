"use client";
import { useState } from "react";
import { Button, Dropdown, Avatar, Badge, Tooltip, Input } from "@arco-design/web-react";
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

export default function Header() {
  const { toggleSidebar, theme, setTheme, locale, setLocale } = useAppStore();
  const { user, logout } = useAuthStore();
  const router = useRouter();
  const t = useTranslations();
  const [searchVisible, setSearchVisible] = useState(false);
  const [globalSearch, setGlobalSearch] = useState("");

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

        {/* Notification */}
        <Tooltip content={t("common.notifications")}>
          <Badge count={0} dot>
            <Button
              type="text"
              icon={<IconNotification />}
              className="arco-btn-icon-only"
              style={{ color: "var(--text-color-3)" }}
            />
          </Badge>
        </Tooltip>

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
