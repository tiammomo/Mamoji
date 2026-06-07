"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Button, Card, Empty, Message, Progress, Skeleton, Tag } from "@arco-design/web-react";
import {
  IconCalendar,
  IconCheckCircle,
  IconExclamationCircle,
  IconFile,
  IconIdcard,
  IconRight,
  IconSearch,
  IconTrophy,
  IconUserGroup,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount } from "@/lib/utils/format";
import type { Employee, EnterpriseSummary } from "@/lib/types";

type BenefitStatus = "ready" | "needs_data" | "watch" | "not_applicable";

type BenefitPolicy = {
  key: string;
  title: string;
  category: string;
  status: BenefitStatus;
  owner: string;
  amount: number | null;
  period: string;
  description: string;
  matchLogic: string;
  dataNeeds: string[];
  materials: string[];
  sourceName: string;
  sourceUrl: string;
  icon: ReactNode;
};

const statusMeta: Record<BenefitStatus, { label: string; color: string; score: number }> = {
  ready: { label: "可准备", color: "green", score: 88 },
  needs_data: { label: "待补资料", color: "orange", score: 56 },
  watch: { label: "持续关注", color: "arcoblue", score: 72 },
  not_applicable: { label: "暂不匹配", color: "gray", score: 35 },
};

const benefitPolicies: BenefitPolicy[] = [
  {
    key: "skill-training",
    title: "职业技能培训补贴",
    category: "技能提升",
    status: "ready",
    owner: "HR / 部门负责人",
    amount: null,
    period: "有效期延长至 2026-12-31",
    description: "面向劳动者、企业、机构等主体，适合规划员工技能提升、专项能力培训和企业培训项目。",
    matchLogic: "公司有在职员工且存在培训计划时，可先进入资料准备和政策确认。",
    dataNeeds: ["员工岗位", "培训计划", "证书/培训项目", "参训记录"],
    materials: ["培训方案", "员工名单", "培训/评价证明", "费用或补贴申请材料"],
    sourceName: "深圳市人力资源和社会保障局",
    sourceUrl: "https://hrss.sz.gov.cn/zmhd/cjwt/cjwt/rsrc/content/post_12647175.html",
    icon: <IconTrophy />,
  },
  {
    key: "graduate-employment",
    title: "高校毕业生就业创业扶持",
    category: "就业创业",
    status: "needs_data",
    owner: "HR / 创始人",
    amount: 10000,
    period: "以最新就业创业扶持清单为准",
    description: "涉及高校毕业生创业、灵活就业社保补贴等机会，适合初创团队补齐员工毕业年份和创业身份信息。",
    matchLogic: "当前员工档案缺少毕业年份、学历、毕业生身份，需补充后判断。",
    dataNeeds: ["毕业年份", "学历/院校", "是否毕业 2/5 年内", "社保连续缴纳情况"],
    materials: ["毕业证或学信材料", "社保缴纳记录", "工商登记信息", "就业登记信息"],
    sourceName: "深圳市人力资源和社会保障局",
    sourceUrl: "https://hrss.sz.gov.cn/ztfw/yshj/jyzcyjd/content/post_12317950.html",
    icon: <IconUserGroup />,
  },
  {
    key: "startup-incubation",
    title: "创业孵化补贴",
    category: "创业扶持",
    status: "watch",
    owner: "创始人 / 行政",
    amount: 3000,
    period: "每年每户，最长不超过 2 年",
    description: "主要面向符合条件的创业孵化基地运营主体或主办单位，入孵企业可关注园区协助申报机会。",
    matchLogic: "公司若处于区级以上创业孵化基地或园区，应维护园区/孵化关系。",
    dataNeeds: ["园区/孵化基地", "入孵日期", "工商登记日期", "孵化协议"],
    materials: ["孵化协议", "工商登记材料", "入孵证明", "园区服务记录"],
    sourceName: "深圳市人力资源和社会保障局",
    sourceUrl: "https://hrss.sz.gov.cn/ztfw/cjjy/cyfw/cybt/content/post_10278721.html",
    icon: <IconFile />,
  },
  {
    key: "social-backpay",
    title: "社保补缴/补登记",
    category: "用工合规",
    status: "ready",
    owner: "HR / 财务",
    amount: null,
    period: "按补缴所属期和经办口径判断",
    description: "覆盖两年内补缴、补差、2024 年 7 月后补登记和税务申报缴费等场景。",
    matchLogic: "入职、离职、转正和基数调整时，应自动检查是否存在漏缴或补差。",
    dataNeeds: ["劳动合同", "入离职日期", "工资流水", "补缴所属期", "缴费基数"],
    materials: ["补缴/补登记申请表", "承诺书", "劳动关系材料", "工资流水或个税记录", "经办人/员工证件"],
    sourceName: "深圳市社会保险基金管理局",
    sourceUrl: "https://hrss.sz.gov.cn/szsi/zxbs/zdyw/ywjs/",
    icon: <IconCalendar />,
  },
  {
    key: "housing-fund-compliance",
    title: "住房公积金比例校验",
    category: "福利合规",
    status: "watch",
    owner: "HR / 财务",
    amount: null,
    period: "2026-04-01 起施行管理办法口径",
    description: "关注个人缴存比例不能低于单位缴存比例等公积金规则，避免员工福利口径异常。",
    matchLogic: "人员薪酬已维护个人和公司比例，可在发薪前进行批量校验。",
    dataNeeds: ["公积金基数", "个人比例", "单位比例", "缴存年月"],
    materials: ["公积金缴存清册", "工资基数依据", "员工确认记录"],
    sourceName: "深圳政府在线",
    sourceUrl: "https://www.sz.gov.cn/hdjl/ywzsk/jsj/zfgjj/content/post_12703676.html",
    icon: <IconIdcard />,
  },
];

const scoreColor = (score: number) => {
  if (score >= 80) return "var(--color-success)";
  if (score >= 60) return "var(--color-warning)";
  return "var(--color-danger)";
};

export default function BenefitsPage() {
  const router = useRouter();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedPolicy, setSelectedPolicy] = useState<BenefitPolicy>(benefitPolicies[0]);

  useEffect(() => {
    let cancelled = false;

    const loadData = async () => {
      setLoading(true);
      try {
        const [summaryRes, employeesRes] = await Promise.all([
          enterpriseApi.summary(),
          enterpriseApi.employees({ status: "active" }),
        ]);
        if (cancelled) return;
        setSummary(summaryRes.data);
        setEmployees(employeesRes.data);
      } catch {
        if (!cancelled) Message.error("人才福利数据加载失败");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    void loadData();

    return () => {
      cancelled = true;
    };
  }, [activeCompanyId]);

  const model = useMemo(() => {
    const readyCount = benefitPolicies.filter((policy) => policy.status === "ready").length;
    const needsDataCount = benefitPolicies.filter((policy) => policy.status === "needs_data").length;
    const estimatedOpportunity = benefitPolicies.reduce((sum, policy) => sum + (policy.amount || 0), 0);
    const readiness = Math.round(benefitPolicies.reduce((sum, policy) => sum + statusMeta[policy.status].score, 0) / benefitPolicies.length);
    const employeeDataGaps = [
      { label: "毕业年份", ready: false, detail: "用于毕业生补贴、创业身份判断" },
      { label: "学历/证书", ready: false, detail: "用于技能培训和人才政策匹配" },
      { label: "园区/孵化关系", ready: false, detail: "用于创业孵化、园区类政策" },
      { label: "社保连续缴纳", ready: employees.length > 0, detail: "用于就业创业和社保补贴校验" },
    ];

    return {
      readyCount,
      needsDataCount,
      estimatedOpportunity,
      readiness,
      employeeDataGaps,
    };
  }, [employees.length]);

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="人才福利"
        subtitle={summary?.company
          ? `${summary.company.name} · 人才政策、社保补缴、福利补贴和申报资料管理`
          : "人才政策、社保补缴、福利补贴和申报资料管理"}
        icon={<IconTrophy />}
        extra={
          <div className="flex items-center gap-2">
            <Button icon={<IconSearch />} onClick={() => router.push("/policy-center?scope=talent")}>
              政策中心
            </Button>
            <Button type="primary" icon={<IconIdcard />} onClick={() => router.push("/admin/compensation")}>
              人员薪酬
            </Button>
          </div>
        }
      />

      <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Card style={{ borderRadius: 12, minHeight: 142 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>政策准备度</div>
          {loading ? <Skeleton /> : (
            <>
              <div className="mt-3 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{model.readiness}分</div>
              <Progress percent={model.readiness} showText={false} color={scoreColor(model.readiness)} className="mt-3" />
            </>
          )}
        </Card>
        <Card style={{ borderRadius: 12, minHeight: 142 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>可准备政策</div>
          {loading ? <Skeleton /> : (
            <>
              <div className="mt-3 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{model.readyCount}</div>
              <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>可进入资料准备</div>
            </>
          )}
        </Card>
        <Card style={{ borderRadius: 12, minHeight: 142 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>待补资料</div>
          {loading ? <Skeleton /> : (
            <>
              <div className="mt-3 text-2xl font-bold" style={{ color: "var(--color-warning)" }}>{model.needsDataCount}</div>
              <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>需要补齐员工画像字段</div>
            </>
          )}
        </Card>
        <Card style={{ borderRadius: 12, minHeight: 142 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>已知机会金额</div>
          {loading ? <Skeleton /> : (
            <>
              <div className="mt-3">
                <AmountDisplay amount={model.estimatedOpportunity} type={1} size="large" />
              </div>
              <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>仅统计有公开固定金额的政策</div>
            </>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[0.96fr_1.04fr]">
        <Card style={{ borderRadius: 12 }} title="政策机会">
          {loading ? (
            <Skeleton />
          ) : (
            <div className="space-y-3">
              {benefitPolicies.map((policy) => {
                const meta = statusMeta[policy.status];
                const active = selectedPolicy.key === policy.key;
                return (
                  <button
                    key={policy.key}
                    type="button"
                    onClick={() => setSelectedPolicy(policy)}
                    className="flex w-full cursor-pointer items-center gap-3 rounded-xl border bg-transparent p-3 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                    style={{
                      borderColor: active ? "rgba(99, 102, 241, 0.42)" : "var(--border-color-light)",
                      backgroundColor: active ? "rgba(99, 102, 241, 0.08)" : "transparent",
                    }}
                  >
                    <span
                      className="grid h-10 w-10 shrink-0 place-items-center rounded-xl"
                      style={{ backgroundColor: "var(--color-fill-1)", color: active ? "var(--color-primary)" : "var(--text-color-3)" }}
                    >
                      {policy.icon}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-semibold" style={{ color: "var(--text-color-1)" }}>{policy.title}</span>
                      <span className="mt-1 block truncate text-xs" style={{ color: "var(--text-color-3)" }}>{policy.description}</span>
                    </span>
                    <Tag color={meta.color}>{meta.label}</Tag>
                    <IconRight style={{ color: "var(--text-color-4)" }} />
                  </button>
                );
              })}
            </div>
          )}
        </Card>

        <Card style={{ borderRadius: 12 }} title="政策详情">
          <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-xl" style={{ color: "var(--color-primary)" }}>{selectedPolicy.icon}</span>
                  <h3 className="m-0 text-lg font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.title}</h3>
                </div>
                <div className="mt-2 text-sm" style={{ color: "var(--text-color-3)" }}>{selectedPolicy.description}</div>
              </div>
              <Tag color={statusMeta[selectedPolicy.status].color}>{statusMeta[selectedPolicy.status].label}</Tag>
            </div>
            <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-3">
              <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>负责角色</div>
                <div className="mt-1 font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.owner}</div>
              </div>
              <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>政策周期</div>
                <div className="mt-1 font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.period}</div>
              </div>
              <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>金额口径</div>
                <div className="mt-1 font-semibold" style={{ color: "var(--text-color-1)" }}>
                  {selectedPolicy.amount ? formatAmount(selectedPolicy.amount) : "按政策核定"}
                </div>
              </div>
            </div>
            <div className="mt-4 text-sm leading-6" style={{ color: "var(--text-color-2)" }}>{selectedPolicy.matchLogic}</div>
          </div>

          <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div>
              <div className="mb-3 font-medium">资料缺口</div>
              <div className="space-y-2">
                {selectedPolicy.dataNeeds.map((item) => (
                  <div key={item} className="flex items-start gap-2 text-sm" style={{ color: "var(--text-color-2)" }}>
                    <IconExclamationCircle className="mt-0.5 shrink-0" style={{ color: "var(--color-warning)" }} />
                    <span>{item}</span>
                  </div>
                ))}
              </div>
            </div>
            <div>
              <div className="mb-3 font-medium">申请材料</div>
              <div className="space-y-2">
                {selectedPolicy.materials.map((item) => (
                  <div key={item} className="flex items-start gap-2 text-sm" style={{ color: "var(--text-color-2)" }}>
                    <IconCheckCircle className="mt-0.5 shrink-0" style={{ color: "var(--color-success)" }} />
                    <span>{item}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border p-3" style={{ borderColor: "var(--border-color-light)" }}>
            <div className="min-w-0">
              <div className="text-xs" style={{ color: "var(--text-color-3)" }}>官方来源</div>
              <div className="truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.sourceName}</div>
            </div>
            <Button type="outline" onClick={() => window.open(selectedPolicy.sourceUrl, "_blank", "noopener,noreferrer")}>
              打开来源
            </Button>
          </div>
        </Card>
      </div>

      <Card className="mt-6" style={{ borderRadius: 12 }} title="员工政策画像资料">
        {loading ? (
          <Skeleton />
        ) : employees.length === 0 ? (
          <Empty description="暂无在职员工" />
        ) : (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
            {model.employeeDataGaps.map((gap) => (
              <div key={gap.label} className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="mb-3 flex items-center justify-between gap-3">
                  <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{gap.label}</div>
                  <Tag color={gap.ready ? "green" : "orange"}>{gap.ready ? "已有基础数据" : "待补充"}</Tag>
                </div>
                <div className="text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{gap.detail}</div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
