import client from "./client";
import type {
  OverviewStats,
  TrendPoint,
  CategoryStat,
  YearlyReport,
  AssetLiability,
  ComparisonData,
  AdvancedInsight,
} from "@/lib/types";

export const statsApi = {
  overview: (params?: { month?: string }) =>
    client.get<OverviewStats>("/stats/overview", { params }),
  trend: (params?: { months?: number; period?: "month" | "quarter" | "year"; limit?: number }) =>
    client.get<TrendPoint[]>("/stats/trend", { params }),
  category: (params: { type: "income" | "expense"; startDate?: string; endDate?: string }) =>
    client.get<CategoryStat[]>("/stats/category", { params }),
  yearly: (year: number) => client.get<YearlyReport>("/stats/yearly", { params: { year } }),
  assetLiability: () => client.get<AssetLiability>("/stats/asset-liability"),
  comparison: (params: { month: string }) =>
    client.get<{ mom: ComparisonData; yoy: ComparisonData }>("/stats/comparison", { params }),
  insights: () => client.get<AdvancedInsight>("/stats/insights"),
};
