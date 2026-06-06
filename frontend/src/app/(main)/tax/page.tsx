"use client";
import { useEffect, useMemo, useState } from "react";
import { Button, Card, Grid, Message, Skeleton, Tag } from "@arco-design/web-react";
import {
  IconCalendarClock,
  IconCheckCircle,
  IconExclamationCircle,
  IconFile,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { useAppStore } from "@/lib/stores/appStore";
import { formatDate } from "@/lib/utils/format";
import type { EnterpriseSummary, TaxItem } from "@/lib/types";

const { Row, Col } = Grid;

const taxTypeLabels: Record<string, string> = {
  vat: "增值税",
  corporate_income_tax: "企业所得税",
  personal_income_tax: "个人所得税",
  surcharge: "附加税",
  stamp_duty: "印花税",
};

const statusLabels: Record<string, { label: string; color: string }> = {
  estimated: { label: "预估", color: "arcoblue" },
  pending: { label: "待缴", color: "orange" },
  paid: { label: "已缴", color: "green" },
  overdue: { label: "逾期", color: "red" },
};

const currencyAmount = (value: number) => Math.max(value || 0, 0);

export default function TaxPage() {
  const router = useRouter();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [taxItems, setTaxItems] = useState<TaxItem[]>([]);
  const [loading, setLoading] = useState(true);
  const taxPagination = useClientPagination(taxItems, 10);

  useEffect(() => {
    let cancelled = false;

    const run = async () => {
      try {
        setLoading(true);
        const [summaryRes, taxRes] = await Promise.all([
          enterpriseApi.summary(),
          enterpriseApi.taxItems(),
        ]);
        if (cancelled) return;
        setSummary(summaryRes.data);
        setTaxItems(taxRes.data);
      } catch {
        if (!cancelled) {
          Message.error("税务数据加载失败");
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void run();

    return () => {
      cancelled = true;
    };
  }, [activeCompanyId]);

  const pendingItems = useMemo(
    () => taxItems.filter((item) => item.status !== "paid"),
    [taxItems]
  );
  const pendingAmount = useMemo(
    () => pendingItems.reduce((sum, item) => sum + currencyAmount(item.taxAmount - item.paidAmount), 0),
    [pendingItems]
  );
  const paidAmount = useMemo(
    () => taxItems.reduce((sum, item) => sum + Math.min(item.paidAmount || 0, item.taxAmount || 0), 0),
    [taxItems]
  );
  const nextDueDate = useMemo(
    () => pendingItems.map((item) => item.dueDate).sort()[0] || null,
    [pendingItems]
  );
  const overdueCount = useMemo(() => {
    const today = new Date().toISOString().slice(0, 10);
    return pendingItems.filter((item) => item.dueDate < today).length;
  }, [pendingItems]);

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title="税务管理"
        subtitle={summary?.company
          ? `${summary.company.name} · ${summary.company.taxpayerType} · ${summary.company.operatingRegion || "地区待完善"}`
          : "税期申报、税费估算与合规风险"}
        icon={<IconFile />}
        extra={
          <Button type="primary" icon={<IconFile />} onClick={() => router.push("/receipts")}>
            票据凭证
          </Button>
        }
      />

      <Row gutter={16} className="mb-6">
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="flex items-center justify-between mb-3">
              <span className="text-sm" style={{ color: "var(--text-color-3)" }}>待处理税费</span>
              <IconExclamationCircle style={{ color: "#f59e0b" }} />
            </div>
            {loading ? <Skeleton /> : <AmountDisplay amount={pendingAmount} type={2} size="large" />}
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>
              {pendingItems.length} 项待申报或待缴纳
            </div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="flex items-center justify-between mb-3">
              <span className="text-sm" style={{ color: "var(--text-color-3)" }}>下个截止日</span>
              <IconCalendarClock style={{ color: "#6366f1" }} />
            </div>
            {loading ? (
              <Skeleton />
            ) : (
              <div className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
                {nextDueDate ? formatDate(nextDueDate) : "--"}
              </div>
            )}
            <div className="text-xs mt-2" style={{ color: overdueCount > 0 ? "#ef4444" : "var(--text-color-3)" }}>
              {overdueCount > 0 ? `${overdueCount} 项已逾期` : "暂无逾期事项"}
            </div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="flex items-center justify-between mb-3">
              <span className="text-sm" style={{ color: "var(--text-color-3)" }}>已缴税费</span>
              <IconCheckCircle style={{ color: "#10b981" }} />
            </div>
            {loading ? <Skeleton /> : <AmountDisplay amount={paidAmount} type={1} size="large" />}
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>
              来自当前税费事项
            </div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="text-sm mb-3" style={{ color: "var(--text-color-3)" }}>政策画像</div>
            {loading ? (
              <Skeleton />
            ) : (
              <div>
                <div className="text-base font-semibold truncate" style={{ color: "var(--text-color-1)" }}>
                  {summary?.company?.policyProfileKey || "--"}
                </div>
                <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>
                  财年起始月 {summary?.company?.fiscalYearStartMonth || 1} 月
                </div>
              </div>
            )}
          </Card>
        </Col>
      </Row>

      <Card style={{ borderRadius: 12 }} title="税期与申报事项">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[960px] table-fixed border-collapse text-sm">
            <colgroup>
              <col style={{ width: "24%" }} />
              <col style={{ width: "12%" }} />
              <col style={{ width: "12%" }} />
              <col style={{ width: "14%" }} />
              <col style={{ width: "14%" }} />
              <col style={{ width: "10%" }} />
              <col style={{ width: "14%" }} />
            </colgroup>
            <thead>
              <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                <th className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>事项</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>税期</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>税种</th>
                <th className="px-4 py-3 text-right font-medium" style={{ color: "var(--text-color-2)" }}>应缴</th>
                <th className="px-4 py-3 text-right font-medium" style={{ color: "var(--text-color-2)" }}>待缴</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>状态</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>截止日</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>
                    加载中...
                  </td>
                </tr>
              ) : taxItems.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>
                    暂无税务事项
                  </td>
                </tr>
              ) : taxPagination.pagedData.map((item) => {
                const status = statusLabels[item.status] || { label: item.status, color: "gray" };
                const unpaid = currencyAmount(item.taxAmount - item.paidAmount);
                return (
                  <tr
                    key={item.id}
                    className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                    style={{ borderColor: "var(--border-color-light)" }}
                  >
                    <td className="px-4 py-4 align-middle">
                      <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{item.name}</div>
                      <div className="text-xs mt-1 truncate" style={{ color: "var(--text-color-3)" }}>
                        {item.note || "无备注"}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle whitespace-nowrap">{item.period}</td>
                    <td className="px-4 py-4 text-center align-middle whitespace-nowrap">
                      {taxTypeLabels[item.taxType] || item.taxType}
                    </td>
                    <td className="px-4 py-4 text-right align-middle whitespace-nowrap">
                      <AmountDisplay amount={item.taxAmount} type={2} size="small" />
                    </td>
                    <td className="px-4 py-4 text-right align-middle whitespace-nowrap">
                      <AmountDisplay amount={unpaid} type={unpaid > 0 ? 2 : 1} size="small" />
                    </td>
                    <td className="px-4 py-4 text-center align-middle whitespace-nowrap">
                      <Tag color={status.color}>{status.label}</Tag>
                    </td>
                    <td className="px-4 py-4 text-center align-middle whitespace-nowrap">
                      {formatDate(item.dueDate)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <AppPagination
          current={taxPagination.page}
          pageSize={taxPagination.pageSize}
          total={taxPagination.total}
          pageSizeOptions={[10, 20, 50, 100]}
          onChange={taxPagination.handleChange}
        />
      </Card>
    </div>
  );
}
