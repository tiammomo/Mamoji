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
  module?: string;
  anyPermission?: string[];
};

export type NavigationGroup = {
  labelKey: string;
  items: NavigationItem[];
};

const companyNavigation: NavigationGroup[] = [
  {
    labelKey: "workspaceGroup",
    items: [
      { key: "/dashboard", labelKey: "dashboard", icon: <IconHome />, keywords: ["首页", "工作台", "home", "workspace"], module: "workspace" },
      { key: "/approvals", labelKey: "approvals", icon: <IconCheckCircle />, keywords: ["审批", "待办", "报销", "付款", "approval", "workflow"], module: "approvals", anyPermission: ["approval.manage"] },
    ],
  },
  {
    labelKey: "operationsGroup",
    items: [
      { key: "/operations", labelKey: "operationsOverview", icon: <IconDashboard />, keywords: ["经营", "总览", "operations"], module: "operations", anyPermission: ["operations.read", "reports.read"] },
      { key: "/transactions", labelKey: "transactions", icon: <IconSwap />, keywords: ["流水", "收支", "交易", "ledger", "transaction"], module: "transactions", anyPermission: ["operations.read", "operations.write", "finance.read"] },
      { key: "/budgets", labelKey: "budgets", icon: <IconCalendar />, keywords: ["预算", "budget"], module: "budgets", anyPermission: ["budget.manage", "operations.read"] },
      { key: "/reports", labelKey: "reports", icon: <IconStorage />, keywords: ["报表", "分析", "report", "analysis"], module: "reports", anyPermission: ["reports.read"] },
      { key: "/recurring", labelKey: "recurring", icon: <IconCalendar />, keywords: ["周期", "固定", "提醒", "recurring"], module: "recurring", anyPermission: ["operations.read", "operations.write"] },
    ],
  },
  {
    labelKey: "financeGroup",
    items: [
      { key: "/finance", labelKey: "financeOverview", icon: <IconDashboard />, keywords: ["财务", "资金", "finance"], module: "finance", anyPermission: ["finance.read"] },
      { key: "/accounts", labelKey: "accounts", icon: <IconSafe />, keywords: ["账户", "银行", "现金", "account"], module: "accounts", anyPermission: ["finance.read"] },
      { key: "/receipts", labelKey: "receipts", icon: <IconFile />, keywords: ["票据", "发票", "凭证", "receipt", "voucher"], module: "evidence", anyPermission: ["finance.read"] },
      { key: "/tax", labelKey: "taxManagement", icon: <IconFile />, keywords: ["税务", "申报", "合规", "tax"], module: "tax", anyPermission: ["tax.manage"] },
    ],
  },
  {
    labelKey: "peopleCostGroup",
    items: [
      { key: "/hr/organization", labelKey: "organizationPeople", icon: <IconBranch />, keywords: ["组织", "部门", "人员", "organization", "people"], module: "people-core", anyPermission: ["people.read"] },
      { key: "/admin/compensation", labelKey: "payrollClose", icon: <IconIdcard />, keywords: ["薪酬", "工资", "月结", "payroll", "compensation"], module: "workforce-cost", anyPermission: ["workforce.cost.manage"] },
      { key: "/hr/workforce-cost", labelKey: "workforceCost", icon: <IconDashboard />, keywords: ["人力成本", "部门成本", "趋势", "workforce", "cost"], module: "workforce-cost", anyPermission: ["workforce.cost.read", "workforce.cost.manage"] },
      { key: "/hr/benefits", labelKey: "benefitsManagement", icon: <IconSafe />, keywords: ["福利", "社保", "公积金", "benefits"], module: "talent-suite", anyPermission: ["people.read"] },
      { key: "/hr/performance", labelKey: "performanceManagement", icon: <IconTrophy />, keywords: ["绩效", "performance"], module: "talent-suite", anyPermission: ["people.read"] },
    ],
  },
  {
    labelKey: "systemGroup",
    items: [
      { key: "/settings", labelKey: "settings", icon: <IconSettings />, keywords: ["设置", "偏好", "settings"], module: "settings" },
      { key: "/admin/users", labelKey: "companyPermissions", icon: <IconUserGroup />, keywords: ["公司", "用户", "权限", "成员", "permission", "member"], module: "people-core", anyPermission: ["admin.permissions", "company.manage"] },
      { key: "/policy-center", labelKey: "policyCenter", icon: <IconFile />, keywords: ["政策", "规则", "policy"], module: "policy", anyPermission: ["policy.read"] },
      { key: "/backup", labelKey: "backup", icon: <IconStorage />, keywords: ["备份", "导出", "backup"], adminOnly: true, module: "backup", anyPermission: ["admin.permissions"] },
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

export type NavigationAccess = {
  isAdmin?: boolean;
  permissions?: Iterable<string>;
  modules?: Iterable<string>;
};

const DEFAULT_INTERNAL_MODULES = new Set([
  "workspace", "approvals", "operations", "transactions", "budgets", "reports", "recurring",
  "finance", "accounts", "evidence", "people-core", "workforce-cost", "settings",
]);

export function navigationFor(subjectType: SubjectType, access: NavigationAccess = {}) {
  const source = subjectType === "household" ? householdNavigation : companyNavigation;
  const permissions = new Set(access.permissions || []);
  const modules = access.modules ? new Set(access.modules) : DEFAULT_INTERNAL_MODULES;
  return source
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => {
        if (item.adminOnly && !access.isAdmin) return false;
        if (item.module && !modules.has(item.module)) return false;
        if (item.anyPermission?.length && !access.isAdmin && !item.anyPermission.some((key) => permissions.has(key))) return false;
        return true;
      }),
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
