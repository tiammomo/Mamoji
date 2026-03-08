"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { BarChart3, Calendar, ChevronDown } from "lucide-react";
import {
  BarChart,
  Bar,
  PieChart,
  Pie,
  Cell,
  Tooltip,
  ResponsiveContainer,
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Legend,
  ReferenceLine,
} from "recharts";
import { statsApi, type StatsCategory, type StatsTrend } from "@/lib/api";

const COLORS = ["#6366F1", "#8B5CF6", "#EC4899", "#F59E0B", "#10B981", "#3B82F6", "#F97316", "#14B8A6"];

function toIntegerCurrency(value: number): string {
  return `¥${Math.round(value).toLocaleString()}`;
}

function niceIntegerStep(span: number, segments = 5): number {
  const raw = Math.max(1, span) / Math.max(1, segments);
  const magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
  const normalized = raw / magnitude;
  const niceBase = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
  return Math.max(1, Math.ceil(niceBase * magnitude));
}

function buildIntegerScale(values: number[], forceMinZero = false): { domain: [number, number]; ticks: number[] } {
  if (values.length === 0) {
    return { domain: forceMinZero ? [0, 1000] : [-1000, 1000], ticks: forceMinZero ? [0, 250, 500, 750, 1000] : [-1000, -500, 0, 500, 1000] };
  }

  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = Math.max(1, max - min);
  const pad = span * 0.12;
  const roughMin = forceMinZero ? Math.max(0, min - pad) : min - pad;
  const roughMax = max + pad;
  const step = niceIntegerStep(roughMax - roughMin);
  const lower = forceMinZero ? 0 : Math.floor(roughMin / step) * step;
  const upper = Math.ceil(roughMax / step) * step;
  const ticks: number[] = [];
  for (let value = lower; value <= upper; value += step) {
    ticks.push(value);
  }
  return { domain: [lower, upper], ticks };
}

export default function ReportsPage() {
  const router = useRouter();
  const [trend, setTrend] = useState<StatsTrend[]>([]);
  const [incomeCategoryStats, setIncomeCategoryStats] = useState<StatsCategory[]>([]);
  const [expenseCategoryStats, setExpenseCategoryStats] = useState<StatsCategory[]>([]);
  const [showDateFilter, setShowDateFilter] = useState(false);
  const [loading, setLoading] = useState(true);
  const [dateRange, setDateRange] = useState({
    start: new Date(new Date().getFullYear(), new Date().getMonth(), 1).toISOString().slice(0, 10),
    end: new Date().toISOString().slice(0, 10),
  });

  useEffect(() => {
    const token = localStorage.getItem("token");
    if (!token) {
      router.push("/login");
    }
  }, [router]);

  useEffect(() => {
    let mounted = true;
    setLoading(true);

    Promise.all([
      statsApi.getTrend(dateRange.start, dateRange.end),
      statsApi.getCategories(1, dateRange.start, dateRange.end),
      statsApi.getCategories(2, dateRange.start, dateRange.end),
    ])
      .then(([trendData, incomeCategoryData, expenseCategoryData]) => {
        if (!mounted) {
          return;
        }
        setTrend(trendData);
        setIncomeCategoryStats(incomeCategoryData);
        setExpenseCategoryStats(expenseCategoryData);
      })
      .catch((error) => {
        console.error("获取报表数据失败:", error);
      })
      .finally(() => {
        if (mounted) {
          setLoading(false);
        }
      });

    return () => {
      mounted = false;
    };
  }, [dateRange]);

  const totalIncome = useMemo(() => trend.reduce((sum, item) => sum + item.income, 0), [trend]);
  const totalExpense = useMemo(() => trend.reduce((sum, item) => sum + item.expense, 0), [trend]);
  const balance = totalIncome - totalExpense;
  const trendWithBalance = useMemo(() => {
    let incomeTotal = 0;
    let expenseTotal = 0;
    const cumulative = trend.map((item) => {
      incomeTotal += item.income;
      expenseTotal += item.expense;
      return {
        month: item.month,
        income: incomeTotal,
        expense: -expenseTotal,
        balance: incomeTotal - expenseTotal,
      };
    });
    return [{ month: "起始", income: 0, expense: 0, balance: 0 }, ...cumulative];
  }, [trend]);
  const barScale = useMemo(() => {
    const values = trend.flatMap((item) => [item.income, item.expense]);
    return buildIntegerScale(values, true);
  }, [trend]);
  const lineScale = useMemo(() => {
    const values = trendWithBalance.flatMap((item) => [item.income, item.expense, item.balance]);
    return buildIntegerScale(values, false);
  }, [trendWithBalance]);
  const topIncomeCategories = useMemo(
    () => [...incomeCategoryStats].sort((a, b) => b.amount - a.amount).slice(0, 5),
    [incomeCategoryStats]
  );
  const topExpenseCategories = useMemo(
    () => [...expenseCategoryStats].sort((a, b) => b.amount - a.amount).slice(0, 5),
    [expenseCategoryStats]
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="animate-spin rounded-full h-12 w-12 border-t-2 border-b-2 border-indigo-600" />
      </div>
    );
  }

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">统计报表</h1>
          <p className="text-gray-500 mt-1">分析你的收支变化</p>
        </div>

        <button
          onClick={() => router.push("/reports/advanced")}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl hover:bg-indigo-700"
        >
          <BarChart3 className="w-5 h-5" />
          高级报表
        </button>

        <div className="relative">
          <button
            onClick={() => setShowDateFilter((prev) => !prev)}
            className="flex items-center gap-2 px-4 py-2 border border-gray-200 rounded-xl hover:bg-gray-50 bg-white"
          >
            <Calendar className="w-5 h-5 text-gray-400" />
            <span className="text-sm">{dateRange.start} ~ {dateRange.end}</span>
            <ChevronDown className={`w-4 h-4 text-gray-400 transition-transform ${showDateFilter ? "rotate-180" : ""}`} />
          </button>

          {showDateFilter && (
            <div className="absolute right-0 top-full mt-2 p-4 bg-white rounded-xl shadow-lg border z-10">
              <div className="flex items-center gap-2">
                <input
                  type="date"
                  value={dateRange.start}
                  onChange={(e) => setDateRange((prev) => ({ ...prev, start: e.target.value }))}
                  className="px-3 py-2 border border-gray-200 rounded-lg text-sm"
                />
                <span className="text-gray-400">至</span>
                <input
                  type="date"
                  value={dateRange.end}
                  onChange={(e) => setDateRange((prev) => ({ ...prev, end: e.target.value }))}
                  className="px-3 py-2 border border-gray-200 rounded-lg text-sm"
                />
              </div>
              <div className="flex justify-end gap-2 mt-3">
                <button
                  onClick={() => {
                    const today = new Date();
                    setDateRange({
                      start: new Date(today.getFullYear(), today.getMonth(), 1).toISOString().slice(0, 10),
                      end: today.toISOString().slice(0, 10),
                    });
                    setShowDateFilter(false);
                  }}
                  className="px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 rounded-lg"
                >
                  本月
                </button>
                <button
                  onClick={() => setShowDateFilter(false)}
                  className="px-3 py-1.5 text-sm bg-indigo-600 text-white rounded-lg hover:bg-indigo-700"
                >
                  确定
                </button>
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
        <SummaryCard title="总收入" value={totalIncome} color="text-green-600" />
        <SummaryCard title="总支出" value={totalExpense} color="text-red-600" />
        <SummaryCard title="结余" value={balance} color={balance >= 0 ? "text-indigo-600" : "text-red-600"} />
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6 mb-6">
        <div className="bg-white rounded-2xl p-6 shadow-sm border border-gray-100">
          <h2 className="text-lg font-semibold mb-4">收支柱状对比</h2>
          <ResponsiveContainer width="100%" height={300}>
            <BarChart data={trend} margin={{ top: 8, right: 8, bottom: 8, left: 18 }}>
              <CartesianGrid strokeDasharray="4 4" stroke="#E5E7EB" />
              <XAxis dataKey="month" />
              <YAxis
                allowDecimals={false}
                width={84}
                tickMargin={8}
                domain={barScale.domain}
                ticks={barScale.ticks}
                tickFormatter={(value: number) => toIntegerCurrency(value)}
              />
              <Tooltip
                formatter={(value: number, name: string) => [toIntegerCurrency(value), name]}
                contentStyle={{ borderRadius: 12, borderColor: "#E5E7EB", boxShadow: "0 10px 30px rgba(0,0,0,0.08)" }}
              />
              <Legend />
              <Bar dataKey="income" name="总收入" fill="#10B981" radius={[8, 8, 0, 0]} maxBarSize={38} />
              <Bar dataKey="expense" name="总支出" fill="#EF4444" radius={[8, 8, 0, 0]} maxBarSize={38} />
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div className="bg-white rounded-2xl p-6 shadow-sm border border-gray-100">
          <h2 className="text-lg font-semibold mb-4">收支趋势</h2>
          <ResponsiveContainer width="100%" height={300}>
            <LineChart data={trendWithBalance} margin={{ top: 8, right: 8, bottom: 8, left: 18 }}>
              <CartesianGrid strokeDasharray="4 4" stroke="#E5E7EB" />
              <XAxis dataKey="month" />
              <YAxis
                allowDecimals={false}
                width={84}
                tickMargin={8}
                domain={lineScale.domain}
                ticks={lineScale.ticks}
                tickFormatter={(value: number) => toIntegerCurrency(value)}
              />
              <ReferenceLine y={0} stroke="#9CA3AF" strokeDasharray="3 3" />
              <Tooltip
                formatter={(value: number, name: string) => [toIntegerCurrency(value), name]}
                contentStyle={{ borderRadius: 12, borderColor: "#E5E7EB", boxShadow: "0 10px 30px rgba(0,0,0,0.08)" }}
              />
              <Legend />
              <Line type="monotone" dataKey="income" name="总收入" stroke="#10B981" strokeWidth={3} dot={false} activeDot={{ r: 4 }} />
              <Line type="monotone" dataKey="expense" name="总支出" stroke="#EF4444" strokeWidth={3} dot={false} activeDot={{ r: 4 }} />
              <Line
                type="monotone"
                dataKey="balance"
                name="结余"
                stroke="#7C3AED"
                strokeWidth={2.5}
                strokeDasharray="6 4"
                dot={false}
                activeDot={{ r: 4 }}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <div className="bg-white rounded-2xl p-6 shadow-sm">
          <h2 className="text-lg font-semibold mb-4">支出分类</h2>
          {expenseCategoryStats.length > 0 ? (
            <ResponsiveContainer width="100%" height={320}>
              <PieChart>
                <Pie
                  data={expenseCategoryStats}
                  dataKey="amount"
                  nameKey="categoryName"
                  cx="50%"
                  cy="50%"
                  outerRadius={100}
                  label={({ categoryName, percentage }) => `${categoryName} ${percentage.toFixed(0)}%`}
                >
                  {expenseCategoryStats.map((_, index) => (
                    <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                  ))}
                </Pie>
                <Tooltip formatter={(value: number) => `¥${value.toLocaleString()}`} />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <div className="h-[320px] flex items-center justify-center text-gray-500">暂无数据</div>
          )}
        </div>

        <div className="bg-white rounded-2xl p-6 shadow-sm">
          <h2 className="text-lg font-semibold mb-4">大类 Top</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <TopCategoryList title="收入 Top" items={topIncomeCategories} amountColor="text-green-600" />
            <TopCategoryList title="支出 Top" items={topExpenseCategories} amountColor="text-red-600" />
          </div>
        </div>
      </div>
    </div>
  );
}

function SummaryCard({ title, value, color }: { title: string; value: number; color: string }) {
  return (
    <div className="bg-white rounded-2xl p-6 shadow-sm border border-gray-100">
      <p className="text-gray-500 text-sm mb-2">{title}</p>
      <p className={`text-2xl font-bold ${color}`}>¥{value.toLocaleString("zh-CN", { minimumFractionDigits: 2 })}</p>
    </div>
  );
}

function TopCategoryList({
  title,
  items,
  amountColor,
}: {
  title: string;
  items: StatsCategory[];
  amountColor: string;
}) {
  return (
    <div>
      <h3 className="text-sm font-semibold text-gray-700 mb-3">{title}</h3>
      <div className="space-y-2">
        {items.length === 0 ? (
          <div className="text-sm text-gray-500">暂无数据</div>
        ) : (
          items.map((item, index) => (
            <div key={item.categoryId} className="flex items-center justify-between rounded-lg border border-gray-100 px-3 py-2">
              <div className="flex items-center gap-2">
                <span className="inline-flex w-5 justify-center text-xs text-gray-400">{index + 1}</span>
                <span className="text-sm text-gray-700">{item.categoryName}</span>
              </div>
              <span className={`text-sm font-semibold ${amountColor}`}>
                ¥{item.amount.toLocaleString("zh-CN", { minimumFractionDigits: 2 })}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
