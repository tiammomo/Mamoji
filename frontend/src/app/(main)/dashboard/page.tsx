"use client";
import { useEffect, useState } from "react";
import { Card, Grid, Button, Skeleton } from "@arco-design/web-react";
import {
  IconRight,
  IconArrowRise,
  IconArrowFall,
  IconCalendar,
  IconCheckCircle,
  IconDashboard,
  IconEdit,
  IconEmpty,
  IconExclamationCircle,
  IconFile,
  IconHome,
  IconIdcard,
  IconSafe,
  IconStorage,
  IconSwap,
  IconUserGroup,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { statsApi } from "@/lib/api/stats";
import { transactionApi } from "@/lib/api/transactions";
import { budgetApi } from "@/lib/api/budgets";
import { enterpriseApi } from "@/lib/api/enterprise";
import AmountDisplay from "@/components/common/AmountDisplay";
import BudgetProgress from "@/components/common/BudgetProgress";
import { useAppStore } from "@/lib/stores/appStore";
import { formatDate } from "@/lib/utils/format";
import type { OverviewStats, Transaction, Budget, EnterpriseSummary, EntityTransfer } from "@/lib/types";

const { Row, Col } = Grid;

const transferTypeLabelKeys: Record<string, string> = {
  shareholder_advance: "transferShareholderAdvance",
  advance_repayment: "transferAdvanceRepayment",
  expense_reimbursement: "transferExpenseReimbursement",
  reimbursement_payment: "transferReimbursementPayment",
  inter_entity_transfer: "transferInterEntity",
};

export default function DashboardPage() {
  const router = useRouter();
  const t = useTranslations("dashboard");
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [stats, setStats] = useState<OverviewStats | null>(null);
  const [enterpriseSummary, setEnterpriseSummary] = useState<EnterpriseSummary | null>(null);
  const [recentTx, setRecentTx] = useState<Transaction[]>([]);
  const [entityTransfers, setEntityTransfers] = useState<EntityTransfer[]>([]);
  const [alerts, setAlerts] = useState<Budget[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    const loadDashboard = async () => {
      setLoading(true);
      const transferRequest = activeCompanyId
        ? enterpriseApi.entityTransfers({ entityId: activeCompanyId })
        : enterpriseApi.entityTransfers();
      const [statsResult, enterpriseResult, transactionResult, budgetResult, transferResult] = await Promise.allSettled([
        statsApi.overview(),
        enterpriseApi.summary(),
        transactionApi.list({ page: 0, size: 5 }),
        budgetApi.active(),
        transferRequest,
      ]);

      if (cancelled) return;

      if (statsResult.status === "fulfilled") {
        setStats(statsResult.value.data);
      }
      if (enterpriseResult.status === "fulfilled") {
        setEnterpriseSummary(enterpriseResult.value.data);
      }
      if (transactionResult.status === "fulfilled") {
        setRecentTx(transactionResult.value.data.content);
      }
      if (budgetResult.status === "fulfilled") {
        setAlerts(
          budgetResult.value.data.filter((b) => b.riskLevel === "high" || b.riskLevel === "critical")
        );
      }
      if (transferResult.status === "fulfilled") {
        setEntityTransfers(transferResult.value.data);
      } else {
        setEntityTransfers([]);
      }
      setLoading(false);
    };

    void loadDashboard();

    return () => {
      cancelled = true;
    };
  }, [activeCompanyId]);

  const quickActions = [
    {
      icon: <IconEdit />,
      label: t("quickNewRecord"),
      path: "/transactions?action=new",
      color: "#6366f1",
      bg: "linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)",
    },
    {
      icon: <IconSwap />,
      label: t("quickLedger"),
      path: "/transactions",
      color: "#10b981",
      bg: "linear-gradient(135deg, #10b981 0%, #059669 100%)",
    },
    {
      icon: <IconDashboard />,
      label: t("quickReports"),
      path: "/reports",
      color: "#3b82f6",
      bg: "linear-gradient(135deg, #3b82f6 0%, #2563eb 100%)",
    },
    {
      icon: <IconCalendar />,
      label: t("quickBudgets"),
      path: "/budgets",
      color: "#f59e0b",
      bg: "linear-gradient(135deg, #f59e0b 0%, #d97706 100%)",
    },
    {
      icon: <IconUserGroup />,
      label: t("quickPeople"),
      path: "/admin/users",
      color: "#06b6d4",
      bg: "linear-gradient(135deg, #06b6d4 0%, #0891b2 100%)",
    },
  ];

  const statCards = [
    {
      label: t("monthlyIncome"),
      value: stats?.monthlyIncome || 0,
      type: 1 as const,
      icon: <IconSafe />,
      iconColor: "#059669",
      iconBg: "#10b98118",
      gradient: "var(--gradient-income)",
      trend: "+12%",
      trendUp: true,
    },
    {
      label: t("monthlyExpense"),
      value: stats?.monthlyExpense || 0,
      type: 2 as const,
      icon: <IconSwap />,
      iconColor: "#dc2626",
      iconBg: "#ef444418",
      gradient: "var(--gradient-expense)",
      trend: "-5%",
      trendUp: false,
    },
    {
      label: t("monthlyBalance"),
      value: stats?.monthlyBalance || 0,
      type: (stats?.monthlyBalance && stats.monthlyBalance >= 0 ? 1 : 2) as 1 | 2,
      icon: <IconDashboard />,
      iconColor: "#4f46e5",
      iconBg: "#6366f118",
      gradient: "var(--gradient-primary)",
      trend: stats?.monthlyBalance && stats.monthlyBalance >= 0 ? "+8%" : "-3%",
      trendUp: stats?.monthlyBalance ? stats.monthlyBalance >= 0 : true,
    },
    {
      label: t("budgetUsage"),
      value: stats?.budgetUsageRate ? stats.budgetUsageRate * 100 : 0,
      icon: <IconCalendar />,
      iconColor: "#d97706",
      iconBg: "#f59e0b18",
      gradient: "var(--gradient-warning)",
      suffix: "%",
      trend: "65%",
      trendUp: true,
    },
  ];
  const activeEntityId = enterpriseSummary?.company?.id ?? activeCompanyId;

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      {/* Welcome banner */}
      <div
        className="rounded-2xl p-6 mb-6 relative overflow-hidden"
        style={{ background: "var(--gradient-primary)" }}
      >
        <div className="relative z-10">
          <h1 className="text-2xl font-bold text-white mb-2">
            {t("welcomeTitle")}
          </h1>
          <p className="text-white/80">
            {enterpriseSummary?.company?.name
              ? t("welcomeSubtitleWithCompany", { company: enterpriseSummary.company.name })
              : t("welcomeSubtitle")}
          </p>
        </div>
        <div className="absolute right-8 top-1/2 -translate-y-1/2 text-8xl opacity-20 text-white">
          <IconDashboard />
        </div>
      </div>

      {/* Stat cards */}
      <Row gutter={16} className="dashboard-card-row mb-6">
        {statCards.map((card, index) => (
          <Col key={index} xs={12} sm={12} md={6} className="dashboard-card-col">
            <div
              className="stat-card dashboard-metric-card animate-fade-in"
              style={{ animationDelay: `${index * 100}ms` }}
            >
              <div className="flex items-start justify-between mb-3">
                <span
                  className="dashboard-card-icon"
                  style={{ color: card.iconColor, backgroundColor: card.iconBg }}
                >
                  {card.icon}
                </span>
                <div
                  className="flex items-center gap-1 text-xs px-2 py-1 rounded-full"
                  style={{
                    backgroundColor: card.trendUp ? "#10b98120" : "#ef444420",
                    color: card.trendUp ? "#10b981" : "#ef4444",
                  }}
                >
                  {card.trendUp ? <IconArrowRise /> : <IconArrowFall />}
                  {card.trend}
                </div>
              </div>
              <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>
                {card.label}
              </div>
              {loading ? (
                <Skeleton />
              ) : (
                <div className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
                  {card.suffix
                    ? `${card.value.toFixed(0)}${card.suffix}`
                    : <AmountDisplay amount={card.value} type={card.type} size="large" />
                  }
                </div>
              )}
            </div>
          </Col>
        ))}
      </Row>

      {/* Enterprise overview */}
      <Row gutter={16} className="dashboard-card-row mb-6">
        <Col xs={12} sm={12} md={6} className="dashboard-card-col">
          <div className="stat-card dashboard-metric-card animate-fade-in">
            <div className="flex items-start justify-between mb-3">
              <span className="dashboard-card-icon" style={{ color: "#4f46e5", backgroundColor: "#6366f118" }}>
                <IconHome />
              </span>
              <span className="text-xs px-2 py-1 rounded-full" style={{ backgroundColor: "#6366f120", color: "#6366f1" }}>
                {t("companyBadge")}
              </span>
            </div>
            <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>{t("companySubject")}</div>
            {loading ? (
              <Skeleton />
            ) : (
              <div>
                <div className="text-base font-semibold truncate" style={{ color: "var(--text-color-1)" }}>
                  {enterpriseSummary?.company?.name || "--"}
                </div>
                <div className="text-xs mt-1 truncate" style={{ color: "var(--text-color-3)" }}>
                  {enterpriseSummary?.company?.taxpayerType || "--"}
                </div>
              </div>
            )}
          </div>
        </Col>
        <Col xs={12} sm={12} md={6} className="dashboard-card-col">
          <div className="stat-card dashboard-metric-card animate-fade-in">
            <div className="flex items-start justify-between mb-3">
              <span className="dashboard-card-icon" style={{ color: "#059669", backgroundColor: "#10b98118" }}>
                <IconUserGroup />
              </span>
              <span className="text-xs px-2 py-1 rounded-full" style={{ backgroundColor: "#10b98120", color: "#10b981" }}>
                {t("peopleBadge")}
              </span>
            </div>
            <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>{t("activeEmployees")}</div>
            {loading ? (
              <Skeleton />
            ) : (
              <div>
                <div className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
                  {enterpriseSummary?.activeEmployeeCount || 0}
                </div>
                <div className="text-xs mt-1" style={{ color: "var(--text-color-3)" }}>
                  {t("pendingOnboarding")} {enterpriseSummary?.onboardingCount || 0} · {t("hiresThisMonth")} {enterpriseSummary?.hiresThisMonth || 0}
                </div>
              </div>
            )}
          </div>
        </Col>
        <Col xs={12} sm={12} md={6} className="dashboard-card-col">
          <div className="stat-card dashboard-metric-card animate-fade-in">
            <div className="flex items-start justify-between mb-3">
              <span className="dashboard-card-icon" style={{ color: "#dc2626", backgroundColor: "#ef444418" }}>
                <IconIdcard />
              </span>
              <span className="text-xs px-2 py-1 rounded-full" style={{ backgroundColor: "#ef444420", color: "#ef4444" }}>
                {t("peopleCostBadge")}
              </span>
            </div>
            <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>{t("monthlyPeopleCost")}</div>
            {loading ? (
              <Skeleton />
            ) : (
              <AmountDisplay amount={enterpriseSummary?.monthlyPeopleCost || 0} type={2} size="large" />
            )}
          </div>
        </Col>
        <Col xs={12} sm={12} md={6} className="dashboard-card-col">
          <div className="stat-card dashboard-metric-card animate-fade-in">
            <div className="flex items-start justify-between mb-3">
              <span className="dashboard-card-icon" style={{ color: "#d97706", backgroundColor: "#f59e0b18" }}>
                <IconFile />
              </span>
              <span className="text-xs px-2 py-1 rounded-full" style={{ backgroundColor: "#f59e0b20", color: "#d97706" }}>
                {t("taxBadge")}
              </span>
            </div>
            <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>{t("pendingTax")}</div>
            {loading ? (
              <Skeleton />
            ) : (
              <div>
                <AmountDisplay amount={enterpriseSummary?.pendingTaxAmount || 0} type={2} size="large" />
                <div className="text-xs mt-1" style={{ color: "var(--text-color-3)" }}>
                  {t("dueDate")} {enterpriseSummary?.nextTaxDueDate || "--"}
                </div>
              </div>
            )}
          </div>
        </Col>
      </Row>

      {/* Entity transfers */}
      <Card
        className="mb-6"
        style={{ borderRadius: 16 }}
        title={
          <div className="flex items-center gap-2">
            <IconSwap />
            <span>{t("entityTransfers")}</span>
          </div>
        }
      >
        {loading ? (
          <Row gutter={16}>
            {[1, 2].map((item) => (
              <Col key={item} xs={24} md={12}>
                <Skeleton style={{ height: 96 }} />
              </Col>
            ))}
          </Row>
        ) : entityTransfers.length === 0 ? (
          <div className="text-center py-10">
            <IconSwap className="mb-4" style={{ fontSize: 42, color: "var(--text-color-4)" }} />
            <p className="font-medium" style={{ color: "var(--text-color-2)" }}>
              {t("noEntityTransfers")}
            </p>
            <p className="text-sm mt-1" style={{ color: "var(--text-color-3)" }}>
              {t("entityTransfersDescription")}
            </p>
          </div>
        ) : (
          <Row gutter={16}>
            {entityTransfers.slice(0, 4).map((transfer, index) => {
              const isInbound = activeEntityId === transfer.toEntityId;
              const isOutbound = activeEntityId === transfer.fromEntityId;
              const amountType: 1 | 2 | undefined = isInbound ? 1 : isOutbound ? 2 : undefined;
              const direction = isInbound ? t("inbound") : isOutbound ? t("outbound") : t("transfer");
              const counterparty = isInbound
                ? transfer.fromEntityName || t("sourceEntity")
                : isOutbound
                  ? transfer.toEntityName || t("targetEntity")
                  : `${transfer.fromEntityName || t("sourceEntity")} → ${transfer.toEntityName || t("targetEntity")}`;

              return (
                <Col key={transfer.id} xs={24} md={12}>
                  <div
                    className="mb-4 rounded-xl border p-4 transition-all hover:shadow-md animate-fade-in"
                    style={{ borderColor: "var(--border-color)", animationDelay: `${index * 80}ms` }}
                  >
                    <div className="flex items-start justify-between gap-4">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 mb-2">
                          <span
                            className="text-xs px-2 py-1 rounded-full shrink-0"
                            style={{
                              backgroundColor: isInbound ? "#10b98120" : isOutbound ? "#ef444420" : "#6366f120",
                              color: isInbound ? "#10b981" : isOutbound ? "#ef4444" : "#6366f1",
                            }}
                          >
                            {direction}
                          </span>
                          <span className="text-sm font-semibold truncate" style={{ color: "var(--text-color-1)" }}>
                            {counterparty}
                          </span>
                        </div>
                        <div className="text-xs" style={{ color: "var(--text-color-3)" }}>
                          {t(transferTypeLabelKeys[transfer.transferType] || "transferInterEntity")} · {formatDate(transfer.transferDate)}
                        </div>
                        {transfer.note ? (
                          <div className="text-sm mt-3 line-clamp-2" style={{ color: "var(--text-color-2)" }}>
                            {transfer.note}
                          </div>
                        ) : null}
                      </div>
                      <span className="shrink-0 text-right">
                        <AmountDisplay
                          amount={transfer.amount}
                          type={amountType}
                          showSign={amountType !== undefined}
                          currency={transfer.currency}
                        />
                      </span>
                    </div>
                  </div>
                </Col>
              );
            })}
          </Row>
        )}
      </Card>

      {/* Quick actions */}
      <Card className="mb-6" style={{ borderRadius: 16 }}>
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold" style={{ color: "var(--text-color-1)" }}>
            {t("quickActions")}
          </h3>
        </div>
        <Row gutter={16}>
          {quickActions.map((action, index) => (
            <Col key={action.path} xs={12} sm={6}>
              <div
                className="quick-action-btn mb-4 animate-fade-in"
                style={{ animationDelay: `${index * 100 + 200}ms` }}
                onClick={() => router.push(action.path)}
              >
                <div
                  className="icon-wrapper text-2xl"
                  style={{ background: action.bg }}
                >
                  {action.icon}
                </div>
                <span className="text-sm font-medium" style={{ color: "var(--text-color-2)" }}>
                  {action.label}
                </span>
              </div>
            </Col>
          ))}
        </Row>
      </Card>

      <Row gutter={16}>
        {/* Recent transactions */}
        <Col xs={24} md={16}>
          <Card
            style={{ borderRadius: 16 }}
            title={
              <div className="flex items-center gap-2">
                <IconStorage />
                <span>{t("recentTransactions")}</span>
              </div>
            }
            extra={
              <Button
                type="text"
                size="small"
                onClick={() => router.push("/transactions")}
                style={{ color: "var(--color-primary)" }}
              >
                {t("viewMore")} <IconRight />
              </Button>
            }
          >
            {loading ? (
              <div className="space-y-4">
                {[1, 2, 3].map((i) => (
                  <Skeleton key={i} style={{ height: 60 }} />
                ))}
              </div>
            ) : recentTx.length === 0 ? (
              <div className="text-center py-12">
                <IconEmpty className="mb-4" style={{ fontSize: 42, color: "var(--text-color-4)" }} />
                <p style={{ color: "var(--text-color-3)" }}>{t("noRecentTransactions")}</p>
                <Button
                  type="primary"
                  className="mt-4"
                  onClick={() => router.push("/transactions?action=new")}
                >
                  {t("quickNewRecord")}
                </Button>
              </div>
            ) : (
              <div className="space-y-2">
                {recentTx.map((tx, index) => (
                  <div
                    key={tx.id}
                    className="transaction-item animate-fade-in"
                    style={{ animationDelay: `${index * 50 + 300}ms` }}
                    onClick={() => router.push("/transactions")}
                  >
                    <div className="flex items-center gap-3">
                      <div
                        className="w-10 h-10 rounded-xl flex items-center justify-center text-lg"
                        style={{
                          backgroundColor: tx.type === 1 ? "#10b98120" : "#ef444420",
                        }}
                      >
                        {tx.categoryIcon || (tx.type === 1 ? "💰" : "💸")}
                      </div>
                      <div>
                        <div className="font-medium text-sm" style={{ color: "var(--text-color-1)" }}>
                          {tx.note || tx.categoryName || t("unnamed")}
                        </div>
                        <div className="text-xs" style={{ color: "var(--text-color-3)" }}>
                          {tx.categoryName} · {formatDate(tx.date)}
                        </div>
                      </div>
                    </div>
                    <AmountDisplay amount={tx.amount} type={tx.type} showSign />
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>

        {/* Budget alerts */}
        <Col xs={24} md={8}>
          <Card
            style={{ borderRadius: 16 }}
            title={
              <div className="flex items-center gap-2">
                <IconExclamationCircle />
                <span>{t("budgetAlerts")}</span>
              </div>
            }
            extra={
              <Button
                type="text"
                size="small"
                onClick={() => router.push("/budgets")}
                style={{ color: "var(--color-primary)" }}
              >
                {t("manage")} <IconRight />
              </Button>
            }
          >
            {loading ? (
              <div className="space-y-4">
                {[1, 2].map((i) => (
                  <Skeleton key={i} style={{ height: 80 }} />
                ))}
              </div>
            ) : alerts.length === 0 ? (
              <div className="text-center py-12">
                <IconCheckCircle className="mb-4" style={{ fontSize: 42, color: "var(--color-success)" }} />
                <p className="font-medium" style={{ color: "var(--text-color-2)" }}>
                  {t("budgetHealthy")}
                </p>
                <p className="text-sm mt-1" style={{ color: "var(--text-color-3)" }}>
                  {t("noBudgetAlerts")}
                </p>
              </div>
            ) : (
              <div className="space-y-4">
                {alerts.map((budget) => (
                  <div
                    key={budget.id}
                    className="p-4 rounded-xl border cursor-pointer hover:shadow-md transition-all"
                    style={{ borderColor: "var(--border-color)" }}
                    onClick={() => router.push("/budgets")}
                  >
                    <div className="flex items-center justify-between mb-2">
                      <span className="font-medium text-sm">{budget.name}</span>
                      <span
                        className="text-xs px-2 py-1 rounded-full"
                        style={{
                          backgroundColor: budget.riskLevel === "critical" ? "#ef444420" : "#f59e0b20",
                          color: budget.riskLevel === "critical" ? "#ef4444" : "#f59e0b",
                        }}
                      >
                        {budget.riskLevel === "critical" ? t("overrun") : t("warning")}
                      </span>
                    </div>
                    <BudgetProgress
                      spent={budget.spent}
                      amount={budget.amount}
                      usageRate={budget.usageRate}
                      warningThreshold={budget.warningThreshold}
                      riskLevel={budget.riskLevel}
                    />
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
