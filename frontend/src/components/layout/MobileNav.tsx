"use client";
import { useRouter, usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import {
  IconDashboard,
  IconFile,
  IconCalendar,
  IconSafe,
  IconSettings,
  IconStorage,
  IconSwap,
  IconUserGroup,
} from "@arco-design/web-react/icon";
import { useAppStore } from "@/lib/stores/appStore";

const companyTabs = [
  { key: "/dashboard", icon: <IconDashboard />, label: "dashboard" },
  { key: "/operations", icon: <IconFile />, label: "operationsOverview" },
  { key: "/reports", icon: <IconStorage />, label: "reports" },
  { key: "/finance", icon: <IconSafe />, label: "financeOverview" },
  { key: "/admin/users", icon: <IconUserGroup />, label: "userManagement" },
  { key: "/settings", icon: <IconSettings />, label: "settings" },
];

const householdTabs = [
  { key: "/dashboard", icon: <IconDashboard />, label: "householdDashboard" },
  { key: "/transactions", icon: <IconSwap />, label: "householdTransactions" },
  { key: "/accounts", icon: <IconSafe />, label: "householdAccounts" },
  { key: "/budgets", icon: <IconCalendar />, label: "householdBudgets" },
  { key: "/reports", icon: <IconStorage />, label: "householdReports" },
  { key: "/settings", icon: <IconSettings />, label: "settings" },
];

export default function MobileNav() {
  const router = useRouter();
  const pathname = usePathname();
  const t = useTranslations("nav");
  const activeSubjectType = useAppStore((state) => state.activeSubjectType);
  const tabs = activeSubjectType === "household" ? householdTabs : companyTabs;

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
        const financeChildActive = activeSubjectType !== "household" && tab.key === "/finance" && ["/accounts", "/receipts"].some((key) => pathname.startsWith(key));
        const operationsChildActive = activeSubjectType !== "household" && tab.key === "/operations" && ["/transactions", "/budgets", "/reports", "/recurring"].some((key) => pathname.startsWith(key));
        const householdTransactionsActive = activeSubjectType === "household" && tab.key === "/transactions" && pathname.startsWith("/recurring");
        const hrChildActive = activeSubjectType !== "household" && tab.key === "/admin/users" && ["/hr/organization", "/admin/compensation", "/hr/benefits", "/hr/performance"].some((key) => pathname.startsWith(key));
        const systemChildActive = tab.key === "/settings" && ["/backup", "/policy-center"].some((key) => pathname.startsWith(key));
        const isActive = pathname === tab.key || pathname.startsWith(tab.key + "/") || financeChildActive || operationsChildActive || householdTransactionsActive || hrChildActive || systemChildActive;
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
