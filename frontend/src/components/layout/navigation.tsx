import type { ReactNode } from "react";
import {
  IconBranch,
  IconCalendar,
  IconCheckCircle,
  IconDashboard,
  IconFile,
  IconHome,
  IconIdcard,
  IconSafe,
  IconSettings,
  IconStorage,
  IconSwap,
  IconTrophy,
  IconUserGroup,
} from "@arco-design/web-react/icon";
import type { SubjectType } from "@/lib/stores/appStore";

export type NavigationItem = {
  key: string;
  labelKey: string;
  icon: ReactNode;
  keywords: string[];
  adminOnly?: boolean;
};

export type NavigationGroup = {
  labelKey: string;
  items: NavigationItem[];
};

const companyNavigation: NavigationGroup[] = [
  {
    labelKey: "workspaceGroup",
    items: [
      { key: "/dashboard", labelKey: "dashboard", icon: <IconHome />, keywords: ["首页", "工作台", "home", "workspace"] },
      { key: "/approvals", labelKey: "approvals", icon: <IconCheckCircle />, keywords: ["审批", "待办", "报销", "付款", "approval", "workflow"] },
    ],
  },
  {
    labelKey: "operationsGroup",
    items: [
      { key: "/operations", labelKey: "operationsOverview", icon: <IconDashboard />, keywords: ["经营", "总览", "operations"] },
      { key: "/transactions", labelKey: "transactions", icon: <IconSwap />, keywords: ["流水", "收支", "交易", "ledger", "transaction"] },
      { key: "/budgets", labelKey: "budgets", icon: <IconCalendar />, keywords: ["预算", "budget"] },
      { key: "/reports", labelKey: "reports", icon: <IconStorage />, keywords: ["报表", "分析", "report", "analysis"] },
      { key: "/recurring", labelKey: "recurring", icon: <IconCalendar />, keywords: ["周期", "固定", "提醒", "recurring"] },
    ],
  },
  {
    labelKey: "financeGroup",
    items: [
      { key: "/finance", labelKey: "financeOverview", icon: <IconDashboard />, keywords: ["财务", "资金", "finance"] },
      { key: "/accounts", labelKey: "accounts", icon: <IconSafe />, keywords: ["账户", "银行", "现金", "account"] },
      { key: "/receipts", labelKey: "receipts", icon: <IconFile />, keywords: ["票据", "发票", "凭证", "receipt", "voucher"] },
    ],
  },
  {
    labelKey: "taxGroup",
    items: [
      { key: "/tax", labelKey: "taxManagement", icon: <IconFile />, keywords: ["税务", "申报", "合规", "tax"] },
    ],
  },
  {
    labelKey: "hrGroup",
    items: [
      { key: "/hr/organization", labelKey: "organizationManagement", icon: <IconBranch />, keywords: ["组织", "部门", "organization"] },
      { key: "/admin/users", labelKey: "userManagement", icon: <IconUserGroup />, keywords: ["员工", "人员", "用户", "employee", "people"] },
      { key: "/admin/compensation", labelKey: "compensationManagement", icon: <IconIdcard />, keywords: ["薪酬", "工资", "payroll", "compensation"] },
      { key: "/hr/benefits", labelKey: "benefitsManagement", icon: <IconSafe />, keywords: ["福利", "社保", "公积金", "benefits"] },
      { key: "/hr/performance", labelKey: "performanceManagement", icon: <IconTrophy />, keywords: ["绩效", "performance"] },
    ],
  },
  {
    labelKey: "systemGroup",
    items: [
      { key: "/settings", labelKey: "settings", icon: <IconSettings />, keywords: ["设置", "偏好", "settings"] },
      { key: "/policy-center", labelKey: "policyCenter", icon: <IconFile />, keywords: ["政策", "规则", "policy"] },
      { key: "/backup", labelKey: "backup", icon: <IconStorage />, keywords: ["备份", "导出", "backup"], adminOnly: true },
    ],
  },
];

const householdNavigation: NavigationGroup[] = [
  {
    labelKey: "householdWorkspaceGroup",
    items: [
      { key: "/dashboard", labelKey: "householdDashboard", icon: <IconHome />, keywords: ["家庭", "首页", "home"] },
    ],
  },
  {
    labelKey: "householdFinanceGroup",
    items: [
      { key: "/transactions", labelKey: "householdTransactions", icon: <IconSwap />, keywords: ["收支", "流水", "transaction"] },
      { key: "/accounts", labelKey: "householdAccounts", icon: <IconSafe />, keywords: ["账户", "资产", "account"] },
      { key: "/budgets", labelKey: "householdBudgets", icon: <IconCalendar />, keywords: ["预算", "budget"] },
      { key: "/reports", labelKey: "householdReports", icon: <IconStorage />, keywords: ["报表", "分析", "report"] },
      { key: "/recurring", labelKey: "householdRecurring", icon: <IconCalendar />, keywords: ["固定", "周期", "recurring"] },
    ],
  },
  {
    labelKey: "systemGroup",
    items: [
      { key: "/settings", labelKey: "settings", icon: <IconSettings />, keywords: ["设置", "settings"] },
      { key: "/backup", labelKey: "backup", icon: <IconStorage />, keywords: ["备份", "导出", "backup"], adminOnly: true },
    ],
  },
];

export function navigationFor(subjectType: SubjectType, isAdmin = false) {
  const source = subjectType === "household" ? householdNavigation : companyNavigation;
  return source
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.adminOnly || isAdmin),
    }))
    .filter((group) => group.items.length > 0);
}

export function flattenNavigation(groups: NavigationGroup[]) {
  return groups.flatMap((group) => group.items);
}

export function activeNavigationKey(pathname: string, items: NavigationItem[]) {
  const exact = items.find((item) => pathname === item.key);
  if (exact) return exact.key;

  return [...items]
    .sort((left, right) => right.key.length - left.key.length)
    .find((item) => pathname.startsWith(`${item.key}/`))?.key || "/dashboard";
}

export function activeNavigationItem(pathname: string, items: NavigationItem[]) {
  const activeKey = activeNavigationKey(pathname, items);
  return items.find((item) => item.key === activeKey) || items[0];
}
