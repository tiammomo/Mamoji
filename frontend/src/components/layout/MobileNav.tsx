"use client";
import { useRouter, usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import {
  IconDashboard,
  IconFile,
  IconSafe,
  IconSettings,
  IconStorage,
  IconUserGroup,
} from "@arco-design/web-react/icon";

const tabs = [
  { key: "/dashboard", icon: <IconDashboard />, label: "dashboard" },
  { key: "/transactions", icon: <IconFile />, label: "transactions" },
  { key: "/reports", icon: <IconStorage />, label: "reports" },
  { key: "/accounts", icon: <IconSafe />, label: "accounts" },
  { key: "/admin/users", icon: <IconUserGroup />, label: "userManagement" },
  { key: "/settings", icon: <IconSettings />, label: "settings" },
];

export default function MobileNav() {
  const router = useRouter();
  const pathname = usePathname();
  const t = useTranslations("nav");

  return (
    <div
      className="fixed bottom-0 left-0 right-0 flex items-center justify-around border-t mobile-nav glass"
      style={{
        height: 64,
        borderColor: "var(--border-color)",
        zIndex: 100,
      }}
    >
      {tabs.map((tab) => {
        const isActive = pathname === tab.key || pathname.startsWith(tab.key + "/");
        return (
          <button
            key={tab.key}
            onClick={() => router.push(tab.key)}
            className="flex flex-col items-center gap-1 bg-transparent border-none cursor-pointer py-2 px-2 transition-all"
            style={{
              color: isActive ? "var(--color-primary)" : "var(--text-color-3)",
              transform: isActive ? "scale(1.1)" : "scale(1)",
            }}
          >
            <span className="text-xl leading-none inline-flex h-6 items-center">{tab.icon}</span>
            <span className="text-xs font-medium">{t(tab.label)}</span>
            {isActive && (
              <div
                className="w-1 h-1 rounded-full mt-0.5"
                style={{ backgroundColor: "var(--color-primary)" }}
              />
            )}
          </button>
        );
      })}
    </div>
  );
}
