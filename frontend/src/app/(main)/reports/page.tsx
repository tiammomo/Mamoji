"use client";
import { useEffect, useState } from "react";
import { Card, Grid, Radio, Skeleton, Tabs, Tag } from "@arco-design/web-react";
import { IconDown, IconUp } from "@arco-design/web-react/icon";
import { useTranslations } from "next-intl";
import { statsApi } from "@/lib/api/stats";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import ReactECharts from "echarts-for-react";
import type {
  AdvancedInsight,
  AssetLiability,
  CategoryStat,
  ComparisonData,
  TrendPoint,
  YearlyReport,
} from "@/lib/types";

const { Row, Col } = Grid;
const TabPane = Tabs.TabPane;

type ReportPeriod = "month" | "quarter" | "year";

const trendLimit: Record<ReportPeriod, number> = {
  month: 6,
  quarter: 4,
  year: 5,
};

const formatDate = (date: Date) => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

const categoryRange = (period: ReportPeriod) => {
  const now = new Date();
  const start = new Date(now);
  if (period === "year") {
    start.setMonth(0, 1);
  } else if (period === "quarter") {
    start.setMonth(Math.floor(now.getMonth() / 3) * 3, 1);
  } else {
    start.setDate(1);
  }
  return {
    startDate: formatDate(start),
    endDate: formatDate(now),
  };
};

function ComparisonCard({
  title,
  data,
  loading,
}: {
  title: string;
  data: ComparisonData | null;
  loading: boolean;
}) {
  return (
    <Card style={{ borderRadius: 16 }}>
      <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>{title}</div>
      {loading ? <Skeleton /> : data ? (
        <div className="flex items-center gap-4">
          <AmountDisplay amount={data.current} size="large" />
          <div className="flex items-center gap-1" style={{ color: data.change >= 0 ? "var(--color-success)" : "var(--color-danger)" }}>
            {data.change >= 0 ? <IconUp /> : <IconDown />}
            <span>{Math.abs(data.changePercent * 100).toFixed(1)}%</span>
          </div>
        </div>
      ) : <div style={{ color: "var(--text-color-3)" }}>--</div>}
    </Card>
  );
}

export default function ReportsPage() {
  const t = useTranslations("report");
  const [period, setPeriod] = useState<ReportPeriod>("month");
  const [trend, setTrend] = useState<TrendPoint[]>([]);
  const [expenseCats, setExpenseCats] = useState<CategoryStat[]>([]);
  const [incomeCats, setIncomeCats] = useState<CategoryStat[]>([]);
  const [overviewLoading, setOverviewLoading] = useState(true);
  const [advancedLoading, setAdvancedLoading] = useState(true);
  const [yearly, setYearly] = useState<YearlyReport | null>(null);
  const [assets, setAssets] = useState<AssetLiability | null>(null);
  const [mom, setMom] = useState<ComparisonData | null>(null);
  const [yoy, setYoy] = useState<ComparisonData | null>(null);
  const [insights, setInsights] = useState<AdvancedInsight | null>(null);

  useEffect(() => {
    let cancelled = false;

    const loadOverview = async () => {
      const range = categoryRange(period);
      const [trendResult, expenseResult, incomeResult] = await Promise.allSettled([
        statsApi.trend({ period, limit: trendLimit[period] }),
        statsApi.category({ type: "expense", ...range }),
        statsApi.category({ type: "income", ...range }),
      ]);

      if (cancelled) return;

      setTrend(trendResult.status === "fulfilled" ? trendResult.value.data : []);
      setExpenseCats(expenseResult.status === "fulfilled" ? expenseResult.value.data : []);
      setIncomeCats(incomeResult.status === "fulfilled" ? incomeResult.value.data : []);
      setOverviewLoading(false);
    };

    void loadOverview();

    return () => {
      cancelled = true;
    };
  }, [period]);

  useEffect(() => {
    let cancelled = false;

    const loadAdvanced = async () => {
      const year = new Date().getFullYear();
      const month = new Date().toISOString().slice(0, 7);
      const [yearlyResult, assetResult, comparisonResult, insightsResult] = await Promise.allSettled([
        statsApi.yearly(year),
        statsApi.assetLiability(),
        statsApi.comparison({ month }),
        statsApi.insights(),
      ]);

      if (cancelled) return;

      setYearly(yearlyResult.status === "fulfilled" ? yearlyResult.value.data : null);
      setAssets(assetResult.status === "fulfilled" ? assetResult.value.data : null);
      if (comparisonResult.status === "fulfilled") {
        setMom(comparisonResult.value.data.mom);
        setYoy(comparisonResult.value.data.yoy);
      } else {
        setMom(null);
        setYoy(null);
      }
      setInsights(insightsResult.status === "fulfilled" ? insightsResult.value.data : null);
      setAdvancedLoading(false);
    };

    void loadAdvanced();

    return () => {
      cancelled = true;
    };
  }, []);

  const trendOption = {
    color: ["#10b981", "#ef4444", "#6366f1"],
    tooltip: {
      trigger: "axis" as const,
      valueFormatter: (value: number | null) => value == null ? "无数据" : `¥${Number(value).toLocaleString()}`,
    },
    legend: { data: ["收入", "成本", "利润"], bottom: 0 },
    grid: { left: 64, right: 32, top: 28, bottom: 56 },
    xAxis: {
      type: "category" as const,
      data: trend.map((item) => item.month),
      axisTick: { alignWithLabel: true },
      axisLine: { lineStyle: { color: "#cbd5e1" } },
    },
    yAxis: {
      type: "value" as const,
      axisLabel: { formatter: (value: number) => value.toLocaleString() },
      splitLine: { lineStyle: { color: "#e2e8f0" } },
    },
    series: [
      {
        name: "收入",
        type: "bar",
        data: trend.map((item) => item.income),
        barMaxWidth: 44,
        itemStyle: { color: "#10b981", borderRadius: [4, 4, 0, 0] },
      },
      {
        name: "成本",
        type: "bar",
        data: trend.map((item) => item.expense),
        barMaxWidth: 44,
        itemStyle: { color: "#ef4444", borderRadius: [4, 4, 0, 0] },
      },
      {
        name: "利润",
        type: "line",
        data: trend.map((item) => (item.hasData ?? (item.income !== 0 || item.expense !== 0)) ? item.balance : null),
        smooth: true,
        connectNulls: false,
        symbol: "circle",
        symbolSize: 8,
        itemStyle: { color: "#6366f1" },
        lineStyle: { width: 2 },
        areaStyle: { color: "rgba(99, 102, 241, 0.10)" },
      },
    ],
  };

  const pieOption = (data: CategoryStat[]) => ({
    tooltip: { trigger: "item" as const, formatter: "{b}: {c} ({d}%)" },
    series: [
      {
        type: "pie",
        radius: ["40%", "70%"],
        data: data.map((d) => ({ name: d.categoryName, value: d.amount })),
        emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: "rgba(0,0,0,0.5)" } },
      },
    ],
  });

  const yearBarOption = yearly
    ? {
        tooltip: { trigger: "axis" as const },
        legend: { data: ["收入", "成本"], bottom: 0 },
        grid: { left: 64, right: 32, top: 28, bottom: 56 },
        xAxis: { type: "category" as const, data: yearly.months.map((m) => `${m.month}月`) },
        yAxis: {
          type: "value" as const,
          axisLabel: { formatter: (value: number) => value.toLocaleString() },
          splitLine: { lineStyle: { color: "#e2e8f0" } },
        },
        series: [
          { name: "收入", type: "bar", data: yearly.months.map((m) => m.income), barMaxWidth: 36, itemStyle: { color: "#10b981", borderRadius: [4, 4, 0, 0] } },
          { name: "成本", type: "bar", data: yearly.months.map((m) => m.expense), barMaxWidth: 36, itemStyle: { color: "#ef4444", borderRadius: [4, 4, 0, 0] } },
        ],
      }
    : null;

  const handlePeriodChange = (value: string) => {
    setOverviewLoading(true);
    setPeriod(value as ReportPeriod);
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader title={t("title")} icon="📊" />

      <Tabs defaultActiveTab="overview">
        <TabPane key="overview" title="概览">
          <div className="flex justify-end mb-4">
            <Radio.Group
              type="button"
              value={period}
              onChange={handlePeriodChange}
              style={{ borderRadius: 12 }}
            >
              <Radio value="month">{t("monthly")}</Radio>
              <Radio value="quarter">{t("quarterly")}</Radio>
              <Radio value="year">{t("yearly")}</Radio>
            </Radio.Group>
          </div>

          {overviewLoading ? (
            <Card style={{ borderRadius: 16 }}>
              <Skeleton className="h-80" />
            </Card>
          ) : (
            <>
              <Card className="mb-6" style={{ borderRadius: 16 }}>
                <div className="flex items-center gap-2 mb-4">
                  <span className="text-xl">📈</span>
                  <h3 className="font-semibold" style={{ color: "var(--text-color-1)" }}>{t("incomeExpense")}</h3>
                </div>
                <ReactECharts option={trendOption} style={{ height: 350 }} />
              </Card>

              <Row gutter={16} className="mb-6">
                <Col xs={24} md={12}>
                  <Card style={{ borderRadius: 16 }}>
                    <div className="flex items-center gap-2 mb-4">
                      <span className="text-xl">💸</span>
                      <h3 className="font-semibold" style={{ color: "var(--text-color-1)" }}>成本分类</h3>
                    </div>
                    {expenseCats.length > 0 ? (
                      <ReactECharts option={pieOption(expenseCats)} style={{ height: 300 }} />
                    ) : (
                      <div className="text-center py-12" style={{ color: "var(--text-color-3)" }}>暂无数据</div>
                    )}
                  </Card>
                </Col>
                <Col xs={24} md={12}>
                  <Card style={{ borderRadius: 16 }}>
                    <div className="flex items-center gap-2 mb-4">
                      <span className="text-xl">💰</span>
                      <h3 className="font-semibold" style={{ color: "var(--text-color-1)" }}>收入分类</h3>
                    </div>
                    {incomeCats.length > 0 ? (
                      <ReactECharts option={pieOption(incomeCats)} style={{ height: 300 }} />
                    ) : (
                      <div className="text-center py-12" style={{ color: "var(--text-color-3)" }}>暂无数据</div>
                    )}
                  </Card>
                </Col>
              </Row>

              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <Card style={{ borderRadius: 16 }}>
                    <div className="flex items-center gap-2 mb-4">
                      <span className="text-xl">🔥</span>
                      <h3 className="font-semibold" style={{ color: "var(--text-color-1)" }}>{t("topExpense")}</h3>
                    </div>
                    <div className="space-y-3">
                      {expenseCats.slice(0, 5).map((cat, i) => (
                        <div key={cat.categoryId} className="flex items-center justify-between p-3 rounded-xl" style={{ backgroundColor: "var(--bg-color-page)" }}>
                          <div className="flex items-center gap-3">
                            <span
                              className="w-8 h-8 rounded-lg flex items-center justify-center text-sm font-bold"
                              style={{
                                backgroundColor: i === 0 ? "#f59e0b20" : i === 1 ? "#94a3b820" : i === 2 ? "#cd7f3220" : "var(--bg-color-card)",
                                color: i === 0 ? "#f59e0b" : i === 1 ? "#94a3b8" : i === 2 ? "#cd7f32" : "var(--text-color-3)",
                              }}
                            >
                              {i + 1}
                            </span>
                            <span>{cat.categoryIcon} {cat.categoryName}</span>
                          </div>
                          <AmountDisplay amount={cat.amount} type={2} />
                        </div>
                      ))}
                    </div>
                  </Card>
                </Col>
                <Col xs={24} md={12}>
                  <Card style={{ borderRadius: 16 }}>
                    <div className="flex items-center gap-2 mb-4">
                      <span className="text-xl">💎</span>
                      <h3 className="font-semibold" style={{ color: "var(--text-color-1)" }}>{t("topIncome")}</h3>
                    </div>
                    <div className="space-y-3">
                      {incomeCats.slice(0, 5).map((cat, i) => (
                        <div key={cat.categoryId} className="flex items-center justify-between p-3 rounded-xl" style={{ backgroundColor: "var(--bg-color-page)" }}>
                          <div className="flex items-center gap-3">
                            <span
                              className="w-8 h-8 rounded-lg flex items-center justify-center text-sm font-bold"
                              style={{
                                backgroundColor: i === 0 ? "#10b98120" : i === 1 ? "#94a3b820" : i === 2 ? "#cd7f3220" : "var(--bg-color-card)",
                                color: i === 0 ? "#10b981" : i === 1 ? "#94a3b8" : i === 2 ? "#cd7f32" : "var(--text-color-3)",
                              }}
                            >
                              {i + 1}
                            </span>
                            <span>{cat.categoryIcon} {cat.categoryName}</span>
                          </div>
                          <AmountDisplay amount={cat.amount} type={1} />
                        </div>
                      ))}
                    </div>
                  </Card>
                </Col>
              </Row>
            </>
          )}
        </TabPane>

        <TabPane key="yearly" title={t("yearlyReport")}>
          {advancedLoading ? (
            <Card style={{ borderRadius: 16 }}><Skeleton className="h-80" /></Card>
          ) : yearBarOption ? (
            <Card title={t("yearlyReport")} style={{ borderRadius: 16 }}>
              <ReactECharts option={yearBarOption} style={{ height: 350 }} />
              <Row gutter={16} className="mt-4">
                <Col span={8}>
                  <div className="text-center">
                    <div className="text-sm" style={{ color: "var(--text-color-3)" }}>年度总收入</div>
                    <AmountDisplay amount={yearly?.totalIncome || 0} type={1} size="large" />
                  </div>
                </Col>
                <Col span={8}>
                  <div className="text-center">
                    <div className="text-sm" style={{ color: "var(--text-color-3)" }}>年度总成本</div>
                    <AmountDisplay amount={yearly?.totalExpense || 0} type={2} size="large" />
                  </div>
                </Col>
                <Col span={8}>
                  <div className="text-center">
                    <div className="text-sm" style={{ color: "var(--text-color-3)" }}>年度总结余</div>
                    <AmountDisplay amount={yearly?.totalBalance || 0} type={1} size="large" />
                  </div>
                </Col>
              </Row>
            </Card>
          ) : null}
        </TabPane>

        <TabPane key="assets" title={t("assetLiability")}>
          {advancedLoading ? (
            <Card style={{ borderRadius: 16 }}><Skeleton className="h-60" /></Card>
          ) : (
            <>
              {assets && (
                <Card className="mb-4" title={t("assetLiability")} style={{ borderRadius: 16 }}>
                  <Row gutter={16}>
                    <Col span={8}>
                      <div className="text-center">
                        <div className="text-sm" style={{ color: "var(--text-color-3)" }}>总资产</div>
                        <AmountDisplay amount={assets.totalAssets} type={1} size="large" />
                      </div>
                    </Col>
                    <Col span={8}>
                      <div className="text-center">
                        <div className="text-sm" style={{ color: "var(--text-color-3)" }}>总负债</div>
                        <AmountDisplay amount={assets.totalLiabilities} type={2} size="large" />
                      </div>
                    </Col>
                    <Col span={8}>
                      <div className="text-center">
                        <div className="text-sm" style={{ color: "var(--text-color-3)" }}>净资产</div>
                        <AmountDisplay amount={assets.netWorth} type={1} size="large" />
                      </div>
                    </Col>
                  </Row>
                </Card>
              )}

              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <ComparisonCard title={`${t("mom")}（本月 vs 上月）`} data={mom} loading={advancedLoading} />
                </Col>
                <Col xs={24} md={12}>
                  <ComparisonCard title={`${t("yoy")}（本月 vs 去年同月）`} data={yoy} loading={advancedLoading} />
                </Col>
              </Row>
            </>
          )}
        </TabPane>

        <TabPane key="insights" title={t("insights")}>
          {advancedLoading ? (
            <Card style={{ borderRadius: 16 }}><Skeleton className="h-60" /></Card>
          ) : insights ? (
            <Card title={t("insights")} style={{ borderRadius: 16 }}>
              {insights.budgetAlerts.length > 0 && (
                <div className="mb-4">
                  <h4 className="mb-2">预算告警</h4>
                  {insights.budgetAlerts.map((alert, i) => (
                    <Tag key={i} color={alert.riskLevel === "critical" ? "red" : "orange"} className="mr-2 mb-2">
                      {alert.name}: {(alert.usageRate * 100).toFixed(0)}%
                    </Tag>
                  ))}
                </div>
              )}
              {insights.largeTransactions.length > 0 ? (
                <div>
                  <h4 className="mb-2">大额交易</h4>
                  {insights.largeTransactions.map((tx) => (
                    <div key={tx.id} className="flex justify-between py-2 border-b" style={{ borderColor: "var(--border-color)" }}>
                      <span>{tx.category} - {tx.date}</span>
                      <AmountDisplay amount={tx.amount} type={2} />
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-center py-12" style={{ color: "var(--text-color-3)" }}>暂无异常洞察</div>
              )}
            </Card>
          ) : null}
        </TabPane>
      </Tabs>
    </div>
  );
}
