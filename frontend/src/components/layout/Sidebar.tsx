"use client";
import type { ReactNode } from "react";
import { useRouter, usePathname } from "next/navigation";
import { Avatar } from "@arco-design/web-react";
import {
  IconFile,
  IconHome,
  IconSwap,
  IconStorage,
  IconSafe,
  IconCalendar,
  IconIdcard,
  IconUserGroup,
  IconSettings,
  IconMenuFold,
  IconMenuUnfold,
} from "@arco-design/web-react/icon";
import { useAppStore } from "@/lib/stores/appStore";
import { useAuthStore } from "@/lib/stores/authStore";
import { useTranslations } from "next-intl";

const menuGroups: Array<{
  labelKey: string;
  items: Array<{ key: string; labelKey: string; icon: ReactNode }>;
}> = [
  {
    labelKey: "workspaceGroup",
    items: [
      { key: "/dashboard", labelKey: "dashboard", icon: <IconHome /> },
    ],
  },
  {
    labelKey: "operationsGroup",
    items: [
      { key: "/transactions", labelKey: "transactions", icon: <IconSwap /> },
      { key: "/budgets", labelKey: "budgets", icon: <IconCalendar /> },
      { key: "/reports", labelKey: "reports", icon: <IconStorage /> },
      { key: "/recurring", labelKey: "recurring", icon: <IconCalendar /> },
    ],
  },
  {
    labelKey: "financeGroup",
    items: [
      { key: "/accounts", labelKey: "accounts", icon: <IconSafe /> },
      { key: "/receipts", labelKey: "receipts", icon: <IconFile /> },
    ],
  },
  {
    labelKey: "taxGroup",
    items: [
      { key: "/tax", labelKey: "taxManagement", icon: <IconFile /> },
    ],
  },
  {
    labelKey: "hrGroup",
    items: [
      { key: "/admin/users", labelKey: "userManagement", icon: <IconUserGroup /> },
      { key: "/admin/compensation", labelKey: "compensationManagement", icon: <IconIdcard /> },
    ],
  },
  {
    labelKey: "systemGroup",
    items: [
      { key: "/settings", labelKey: "settings", icon: <IconSettings /> },
    ],
  },
];

const menuItems = menuGroups.flatMap((group) => group.items);

export default function Sidebar() {
  const router = useRouter();
  const pathname = usePathname();
  const { sidebarCollapsed, toggleSidebar } = useAppStore();
  const { user } = useAuthStore();
  const t = useTranslations("nav");

  const selectedKey = pathname.startsWith("/admin/compensation")
    ? "/admin/compensation"
    : pathname.startsWith("/admin/users")
    ? "/admin/users"
    : pathname.startsWith("/backup") || pathname.startsWith("/admin")
    ? "/settings"
    : menuItems.find((item) => pathname.startsWith(item.key))?.key || "/dashboard";

  const avatarEmoji = user?.avatar?.split("|")[0] || "👤";
  const avatarColor = user?.avatar?.split("|")[1] || "#6366f1";

  return (
    <div
      className="h-full flex flex-col border-r glass"
      style={{
        width: sidebarCollapsed ? 76 : 272,
        borderColor: "var(--border-color)",
        transition: "width 0.3s cubic-bezier(0.4, 0, 0.2, 1)",
      }}
    >
      {/* Logo */}
      <div className="flex items-center justify-between border-b px-4" style={{ height: 72, borderColor: "var(--border-color)" }}>
        {!sidebarCollapsed && (
          <div className="flex items-center gap-3">
            <div
              className="w-11 h-11 rounded-xl flex items-center justify-center text-lg font-bold text-white shadow-sm"
              style={{ background: "linear-gradient(135deg, #2563eb 0%, #6366f1 58%, #7c3aed 100%)" }}
            >
              M
            </div>
            <div>
              <div className="font-bold text-lg" style={{ color: "var(--text-color-1)" }}>
                Mamoji
              </div>
              <div className="text-xs" style={{ color: "var(--text-color-3)" }}>
                {t("appSubtitle")}
              </div>
            </div>
          </div>
        )}
        {sidebarCollapsed && (
          <div
            className="w-11 h-11 rounded-xl flex items-center justify-center text-lg font-bold text-white mx-auto shadow-sm"
            style={{ background: "linear-gradient(135deg, #2563eb 0%, #6366f1 58%, #7c3aed 100%)" }}
          >
            M
          </div>
        )}
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto px-3 py-4">
        <div className="space-y-5">
          {menuGroups.map((group) => (
            <div key={group.labelKey}>
              {!sidebarCollapsed ? (
                <div
                  className="mb-2 px-2 text-[11px] font-semibold uppercase tracking-normal"
                  style={{ color: "var(--text-color-3)" }}
                >
                  {t(group.labelKey)}
                </div>
              ) : (
                <div className="my-2 border-t" style={{ borderColor: "var(--border-color-light)" }} />
              )}
              <div className="space-y-1.5">
                {group.items.map((item) => {
                  const isActive = selectedKey === item.key;
                  const label = t(item.labelKey);

                  return (
                    <button
                      key={item.key}
                      type="button"
                      title={sidebarCollapsed ? label : undefined}
                      aria-current={isActive ? "page" : undefined}
                      onClick={() => router.push(item.key)}
                      className="group relative flex h-11 w-full cursor-pointer items-center rounded-xl border-0 bg-transparent px-2.5 text-left outline-none transition-all hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                      style={{
                        color: isActive ? "var(--color-primary-dark)" : "var(--text-color-2)",
                        backgroundColor: isActive ? "rgba(99, 102, 241, 0.1)" : "transparent",
                        boxShadow: isActive ? "inset 0 0 0 1px rgba(99, 102, 241, 0.12)" : "none",
                      }}
                    >
                      <span
                        className="absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full transition-all"
                        style={{ backgroundColor: isActive ? "var(--color-primary)" : "transparent" }}
                      />
                      <span
                        className="grid h-8 w-8 shrink-0 place-items-center rounded-lg transition-all group-hover:bg-black/[0.04] dark:group-hover:bg-white/[0.06]"
                        style={{
                          backgroundColor: isActive ? "var(--color-primary)" : "rgba(100, 116, 139, 0.08)",
                          color: isActive ? "#ffffff" : "var(--text-color-3)",
                          fontSize: 18,
                        }}
                      >
                        {item.icon}
                      </span>
                      {!sidebarCollapsed && (
                        <span className="ml-3 truncate text-sm font-medium tracking-normal">
                          {label}
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </nav>

      {/* Collapse button */}
      <div className="p-3 border-t" style={{ borderColor: "var(--border-color)" }}>
        <button
          onClick={toggleSidebar}
          className="flex h-10 w-full cursor-pointer items-center justify-center gap-2 rounded-xl border-0 bg-transparent px-3 transition-all hover:bg-black/5 dark:hover:bg-white/5"
          style={{ color: "var(--text-color-3)" }}
        >
          {sidebarCollapsed ? <IconMenuUnfold /> : <IconMenuFold />}
          {!sidebarCollapsed && <span className="text-sm">{t("collapseMenu")}</span>}
        </button>
      </div>

      {/* User info */}
      {!sidebarCollapsed && (
        <div className="p-4 border-t" style={{ borderColor: "var(--border-color)" }}>
          <div className="flex items-center gap-3">
            <Avatar
              size={40}
              style={{ backgroundColor: avatarColor, borderRadius: 12 }}
            >
              {avatarEmoji}
            </Avatar>
            <div className="flex-1 min-w-0">
              <div className="font-medium text-sm truncate" style={{ color: "var(--text-color-1)" }}>
                {user?.nickname || t("userFallback")}
              </div>
              <div className="text-xs truncate" style={{ color: "var(--text-color-3)" }}>
                {user?.email || ""}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
