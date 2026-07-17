"use client";

import { useMemo, useState } from "react";
import { Drawer } from "@arco-design/web-react";
import { IconMore } from "@arco-design/web-react/icon";
import { usePathname, useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useAppStore } from "@/lib/stores/appStore";
import { useAuthStore } from "@/lib/stores/authStore";
import { activeNavigationKey, flattenNavigation, navigationFor } from "./navigation";

const companyPrimaryKeys = ["/dashboard", "/operations", "/finance", "/admin/users"];
const householdPrimaryKeys = ["/dashboard", "/transactions", "/accounts", "/budgets"];

export default function MobileNav() {
  const router = useRouter();
  const pathname = usePathname();
  const t = useTranslations();
  const [moreVisible, setMoreVisible] = useState(false);
  const activeSubjectType = useAppStore((state) => state.activeSubjectType);
  const user = useAuthStore((state) => state.user);
  const accessContext = useAuthStore((state) => state.accessContext);
  const groups = useMemo(
    () => navigationFor(activeSubjectType, {
      isAdmin: user?.role === 1,
      permissions: accessContext?.permissions,
      modules: accessContext?.modules.enabled,
    }),
    [accessContext?.modules.enabled, accessContext?.permissions, activeSubjectType, user?.role]
  );
  const allItems = useMemo(() => flattenNavigation(groups), [groups]);
  const activeKey = activeNavigationKey(pathname, allItems);
  const primaryKeys = activeSubjectType === "household" ? householdPrimaryKeys : companyPrimaryKeys;
  const primaryItems = primaryKeys
    .map((key) => allItems.find((item) => item.key === key))
    .filter((item): item is NonNullable<typeof item> => Boolean(item));
  const moreIsActive = !primaryItems.some((item) => item.key === activeKey);

  const navigate = (path: string) => {
    setMoreVisible(false);
    router.push(path);
  };

  return (
    <>
      <nav className="mobile-nav glass" aria-label={t("common.mobileNavigation")}>
        {primaryItems.map((item) => {
          const isActive = item.key === activeKey;
          return (
            <button
              key={item.key}
              type="button"
              onClick={() => navigate(item.key)}
              className="mobile-nav-item"
              aria-current={isActive ? "page" : undefined}
              aria-label={t(`nav.${item.labelKey}`)}
            >
              <span className="mobile-nav-icon" aria-hidden="true">{item.icon}</span>
              <span className="mobile-nav-label">{t(`nav.${item.labelKey}`)}</span>
            </button>
          );
        })}
        <button
          type="button"
          onClick={() => setMoreVisible(true)}
          className="mobile-nav-item"
          aria-expanded={moreVisible}
          aria-label={t("common.moreNavigation")}
          data-active={moreIsActive || moreVisible ? "true" : "false"}
        >
          <span className="mobile-nav-icon" aria-hidden="true"><IconMore /></span>
          <span className="mobile-nav-label">{t("common.more")}</span>
        </button>
      </nav>

      <Drawer
        className="mobile-more-drawer"
        placement="bottom"
        height="min(78vh, 640px)"
        title={t("common.allFeatures")}
        visible={moreVisible}
        footer={null}
        onCancel={() => setMoreVisible(false)}
      >
        <div className="space-y-6 pb-6">
          {groups.map((group) => (
            <section key={group.labelKey} aria-labelledby={`mobile-group-${group.labelKey}`}>
              <h2
                id={`mobile-group-${group.labelKey}`}
                className="mb-3 text-xs font-semibold uppercase tracking-[0.14em]"
                style={{ color: "var(--text-color-3)" }}
              >
                {t(`nav.${group.labelKey}`)}
              </h2>
              <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                {group.items.map((item) => {
                  const isActive = item.key === activeKey;
                  return (
                    <button
                      key={item.key}
                      type="button"
                      onClick={() => navigate(item.key)}
                      className="mobile-feature-item"
                      aria-current={isActive ? "page" : undefined}
                      data-active={isActive ? "true" : "false"}
                    >
                      <span className="mobile-feature-icon" aria-hidden="true">{item.icon}</span>
                      <span className="line-clamp-2 text-center text-xs font-medium">{t(`nav.${item.labelKey}`)}</span>
                    </button>
                  );
                })}
              </div>
            </section>
          ))}
        </div>
      </Drawer>
    </>
  );
}
