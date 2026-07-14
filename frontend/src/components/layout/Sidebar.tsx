"use client";
import { useMemo, useState } from "react";
import { useRouter, usePathname } from "next/navigation";
import { Avatar } from "@arco-design/web-react";
import {
  IconMenuFold,
  IconMenuUnfold,
  IconDown,
  IconRight,
} from "@arco-design/web-react/icon";
import { useAppStore } from "@/lib/stores/appStore";
import { useAuthStore } from "@/lib/stores/authStore";
import { useTranslations } from "next-intl";
import { activeNavigationKey, flattenNavigation, navigationFor, type NavigationGroup } from "./navigation";

const sidebarOpenGroupsStorageKey = "sidebarOpenGroups";

const getSelectedGroupKey = (groups: NavigationGroup[], selectedKey: string) =>
  groups.find((group) => group.items.some((item) => item.key === selectedKey))?.labelKey || groups[0]?.labelKey || "workspaceGroup";

const readStoredOpenGroups = (groups: NavigationGroup[], fallback: string[]) => {
  if (typeof window === "undefined") return fallback;
  const raw = localStorage.getItem(sidebarOpenGroupsStorageKey);
  if (!raw) return fallback;

  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return fallback;
    const validGroupKeys = new Set(groups.map((group) => group.labelKey));
    const stored = parsed.filter((value): value is string => typeof value === "string" && validGroupKeys.has(value));
    return stored.length ? stored : fallback;
  } catch {
    return fallback;
  }
};

export default function Sidebar() {
  const router = useRouter();
  const pathname = usePathname();
  const { sidebarCollapsed, toggleSidebar, activeSubjectType } = useAppStore();
  const { user } = useAuthStore();
  const t = useTranslations("nav");
  const menuGroups = navigationFor(activeSubjectType, user?.role === 1);
  const menuItems = flattenNavigation(menuGroups);
  const selectedKey = activeNavigationKey(pathname, menuItems);
  const selectedGroupKey = getSelectedGroupKey(menuGroups, selectedKey);
  const defaultOpenGroupKeys = [menuGroups[0]?.labelKey || "workspaceGroup", selectedGroupKey].filter((value, index, list) => list.indexOf(value) === index);
  const [openGroupKeys, setOpenGroupKeys] = useState(() => readStoredOpenGroups(menuGroups, defaultOpenGroupKeys));
  const validGroupKeys = useMemo(() => new Set(menuGroups.map((group) => group.labelKey)), [menuGroups]);
  const effectiveOpenGroupKeys = useMemo(() => {
    const sanitized = openGroupKeys.filter((key) => validGroupKeys.has(key));
    return sanitized.includes(selectedGroupKey) ? sanitized : [...sanitized, selectedGroupKey];
  }, [openGroupKeys, selectedGroupKey, validGroupKeys]);

  const avatarEmoji = user?.avatar?.split("|")[0] || "👤";
  const avatarColor = user?.avatar?.split("|")[1] || "#6366f1";

  const toggleGroup = (groupKey: string) => {
    setOpenGroupKeys((current) => {
      const sanitized = current.filter((key) => validGroupKeys.has(key));
      const next = sanitized.includes(groupKey)
        ? sanitized.filter((key) => key !== groupKey)
        : [...sanitized, groupKey];
      if (typeof window !== "undefined") {
        localStorage.setItem(sidebarOpenGroupsStorageKey, JSON.stringify(next));
      }
      return next;
    });
  };

  return (
    <aside
      className="app-sidebar h-full flex flex-col border-r glass"
      aria-label={t("appSubtitle")}
      style={{
        width: sidebarCollapsed ? 76 : 272,
        borderColor: "var(--border-color)",
        transition: "width 0.3s cubic-bezier(0.4, 0, 0.2, 1)",
      }}
    >
      {/* Logo */}
      <button
        type="button"
        className="flex w-full cursor-pointer items-center justify-between border-0 border-b bg-transparent px-4 text-left"
        style={{ height: 68, borderColor: "var(--border-color)" }}
        onClick={() => router.push("/dashboard")}
        aria-label="Mamoji"
      >
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
      </button>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto px-3 py-3" aria-label={t("appSubtitle")}>
        <div className="space-y-3">
          {menuGroups.map((group) => (
            <div key={group.labelKey} className="space-y-1">
              {!sidebarCollapsed ? (
                (() => {
                  const isGroupActive = group.labelKey === selectedGroupKey;
                  const isGroupOpen = effectiveOpenGroupKeys.includes(group.labelKey);

                  return (
                    <button
                      type="button"
                      aria-expanded={isGroupOpen}
                      aria-controls={`sidebar-group-${group.labelKey}`}
                      onClick={() => toggleGroup(group.labelKey)}
                      className="group/section mb-1 flex h-8 w-full cursor-pointer items-center justify-between rounded-lg border-0 bg-transparent px-2 text-left outline-none transition-all hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                      style={{
                        color: isGroupActive ? "var(--color-primary-dark)" : "var(--text-color-3)",
                        backgroundColor: "transparent",
                      }}
                    >
                      <span className="flex min-w-0 items-center gap-2">
                        <span
                          className="h-4 w-0.5 rounded-full transition-colors"
                          style={{ backgroundColor: isGroupActive ? "var(--color-primary)" : "transparent" }}
                        />
                        <span className="min-w-0 truncate text-sm font-semibold tracking-normal">
                          {t(group.labelKey)}
                        </span>
                      </span>
                      <span className="ml-2 flex shrink-0 items-center gap-1.5 text-xs">
                        <span
                          className="grid h-5 min-w-5 place-items-center rounded-full px-1.5"
                          style={{
                            backgroundColor: isGroupActive ? "rgba(99, 102, 241, 0.11)" : "var(--color-fill-1)",
                            color: isGroupActive ? "var(--color-primary-dark)" : "var(--text-color-3)",
                          }}
                        >
                          {group.items.length}
                        </span>
                        <span
                          className="grid h-5 w-5 place-items-center rounded-full transition-colors group-hover/section:bg-black/[0.04] dark:group-hover/section:bg-white/[0.06]"
                          style={{ color: isGroupActive ? "var(--color-primary-dark)" : "var(--text-color-4)" }}
                        >
                          {isGroupOpen ? <IconDown /> : <IconRight />}
                        </span>
                      </span>
                    </button>
                  );
                })()
              ) : (
                <div className="my-1.5 border-t" style={{ borderColor: "var(--border-color-light)" }} />
              )}
              {(sidebarCollapsed || effectiveOpenGroupKeys.includes(group.labelKey)) && (
              <div className="space-y-1" id={`sidebar-group-${group.labelKey}`}>
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
                      className="group relative flex h-11 w-full cursor-pointer items-center rounded-2xl border-0 bg-transparent px-2.5 text-left outline-none transition-all hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                      style={{
                        color: isActive ? "var(--color-primary-dark)" : "var(--text-color-2)",
                        backgroundColor: isActive ? "rgba(99, 102, 241, 0.115)" : "transparent",
                        boxShadow: isActive
                          ? "inset 0 0 0 1px rgba(99, 102, 241, 0.16), 0 8px 18px rgba(79, 70, 229, 0.08)"
                          : "none",
                      }}
                    >
                      <span
                        className="absolute left-0 top-1/2 h-6 w-1 -translate-y-1/2 rounded-r-full transition-all"
                        style={{ backgroundColor: isActive ? "var(--color-primary)" : "transparent" }}
                      />
                      <span
                        className="grid h-8 w-8 shrink-0 place-items-center rounded-xl transition-all group-hover:bg-black/[0.04] dark:group-hover:bg-white/[0.06]"
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
              )}
            </div>
          ))}
        </div>
      </nav>

      {/* Collapse button */}
      <div className="p-2 border-t" style={{ borderColor: "var(--border-color)" }}>
        <button
          type="button"
          onClick={toggleSidebar}
          aria-label={sidebarCollapsed ? t("collapseMenu") : t("collapseMenu")}
          title={t("collapseMenu")}
          className="flex h-9 w-full cursor-pointer items-center justify-center gap-2 rounded-xl border-0 bg-transparent px-3 transition-all hover:bg-black/5 dark:hover:bg-white/5"
          style={{ color: "var(--text-color-3)" }}
        >
          {sidebarCollapsed ? <IconMenuUnfold /> : <IconMenuFold />}
          {!sidebarCollapsed && <span className="text-sm">{t("collapseMenu")}</span>}
        </button>
      </div>

      {/* User info */}
      {!sidebarCollapsed && (
        <div className="p-3 border-t" style={{ borderColor: "var(--border-color)" }}>
          <div className="flex items-center gap-3">
            <Avatar
              size={36}
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
    </aside>
  );
}
