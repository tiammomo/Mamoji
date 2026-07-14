"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Button, Card, Empty, Message, Skeleton, Tag } from "@arco-design/web-react";
import {
  IconCalendar,
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
import { enterpriseApi } from "@/lib/api/enterprise";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount } from "@/lib/utils/format";
import type { Employee, EnterpriseSummary } from "@/lib/types";

type BenefitStatus = "reference_clear" | "needs_profile" | "verify_latest" | "not_applicable";

type BenefitPolicy = {
  key: string;
  title: string;
  category: string;
  referenceStatus: BenefitStatus;
  owner: string;
  publicAmountExample: number | null;
  period: string;
  description: string;
  matchLogic: string;
  dataNeeds: string[];
  materials: string[];
  sourceName: string;
  sourceUrl: string;
  icon: ReactNode;
};

const statusMeta: Record<BenefitStatus, { label: string; color: string }> = {
  reference_clear: { label: "资料方向清晰", color: "green" },
  needs_profile: { label: "需补基础字段", color: "orange" },
  verify_latest: { label: "需核对最新口径", color: "arcoblue" },
  not_applicable: { label: "暂不纳入参考", color: "gray" },
};

const REFERENCE_SNAPSHOT_UPDATED_AT = "2026-07-14";

const benefitPolicies: BenefitPolicy[] = [
  {
    key: "skill-training",
    title: "职业技能培训补贴",
    category: "技能提升",
    referenceStatus: "reference_clear",
    owner: "HR / 部门负责人",
    publicAmountExample: null,
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
    referenceStatus: "needs_profile",
    owner: "HR / 创始人",
    publicAmountExample: 10000,
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
    referenceStatus: "verify_latest",
    owner: "创始人 / 行政",
    publicAmountExample: 3000,
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
    referenceStatus: "reference_clear",
    owner: "HR / 财务",
    publicAmountExample: null,
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
    referenceStatus: "verify_latest",
    owner: "HR / 财务",
    publicAmountExample: null,
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

export default function BenefitsPage() {
  const router = useRouter();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const [selectedPolicy, setSelectedPolicy] = useState<BenefitPolicy>(benefitPolicies[0]);

  useEffect(() => {
    let cancelled = false;

    const loadData = async () => {
      setLoading(true);
      setError(null);
      setSummary(null);
      setEmployees([]);
      try {
        const [summaryRes, employeesRes] = await Promise.all([
          enterpriseApi.summary(),
          enterpriseApi.employees({ status: "active" }),
        ]);
        if (cancelled) return;
        setSummary(summaryRes.data);
        setEmployees(employeesRes.data);
      } catch {
        if (!cancelled) {
          setError("员工档案加载失败，当前无法计算真实的数据字段覆盖情况。");
          Message.error("人才福利参考数据加载失败");
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    void loadData();

    return () => {
      cancelled = true;
    };
  }, [activeCompanyId, reloadToken]);

  const model = useMemo(() => {
    const supportedFields = [
      {
        label: "毕业信息",
        count: employees.filter((employee) => Boolean(
          employee.graduationYear || employee.graduationDate || employee.graduationSchool?.trim()
        )).length,
        detail: "读取毕业年份、毕业日期或院校字段；仅表示已填写。",
      },
      {
        label: "学历与技能",
        count: employees.filter((employee) => Boolean(
          employee.educationLevel?.trim() && employee.skillTags?.trim()
        )).length,
        detail: "读取学历层次和技能标签；不判断证书真实性。",
      },
      {
        label: "社保基础信息",
        count: employees.filter((employee) => Boolean(
          employee.socialInsuranceRegion?.trim() && Number(employee.socialInsuranceBase || 0) > 0
        )).length,
        detail: "读取参保地区和社保基数；不代表连续缴纳。",
      },
      {
        label: "档案人工复核",
        count: employees.filter((employee) => Boolean(employee.profileVerifiedAt)).length,
        detail: "仅统计已有档案复核时间的员工。",
      },
    ];
    const completedFields = supportedFields.reduce((sum, field) => sum + field.count, 0);
    const totalFields = employees.length * supportedFields.length;
    const fieldCoverage = totalFields ? Math.round((completedFields / totalFields) * 100) : 0;
    const sourceCount = new Set(benefitPolicies.map((policy) => policy.sourceUrl)).size;
    const amountExampleCount = benefitPolicies.filter((policy) => policy.publicAmountExample !== null).length;
    const employeeDataFields = [
      ...supportedFields.map((field) => ({ ...field, supported: true as const })),
      {
        label: "园区 / 孵化关系",
        count: null,
        detail: "当前员工档案没有对应字段，需要线下或在后续数据模型中核对。",
        supported: false as const,
      },
    ];

    return {
      fieldCoverage,
      sourceCount,
      amountExampleCount,
      employeeDataFields,
    };
  }, [employees]);

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="人才福利参考台"
        subtitle={summary?.company
          ? `${summary.company.name} · 内置政策参考与员工资料核对，不代表资格认定或办理状态`
          : "内置政策参考与员工资料核对，不代表资格认定或办理状态"}
        icon={<IconTrophy />}
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Button icon={<IconSearch />} onClick={() => router.push("/policy-center?scope=talent")}>
              政策中心
            </Button>
            <Button type="primary" icon={<IconIdcard />} onClick={() => router.push("/admin/compensation")}>
              人员薪酬
            </Button>
          </div>
        }
      />

      <div
        className="mb-5 rounded-2xl border p-4 sm:p-5"
        style={{
          borderColor: "var(--color-warning-border)",
          background: "linear-gradient(135deg, var(--color-warning-soft), rgba(99, 102, 241, 0.07))",
        }}
      >
        <div className="flex items-start gap-3">
          <span
            className="grid h-9 w-9 shrink-0 place-items-center rounded-xl"
            style={{ backgroundColor: "var(--color-warning-soft)", color: "var(--color-warning)" }}
          >
            <IconExclamationCircle />
          </span>
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-semibold" style={{ color: "var(--text-color-1)" }}>参考估算 · 需人工复核</span>
              <Tag color="orange">内置静态快照</Tag>
            </div>
            <div className="mt-1 text-sm leading-6" style={{ color: "var(--text-color-2)" }}>
              条目来自页面内置的公开政策参考，快照整理于 {REFERENCE_SNAPSHOT_UPDATED_AT}，不会自动同步官方变化。
              状态与金额只用于提示核对方向，不代表当前公司符合资格、已申报、已获批或可领取对应金额。
            </div>
          </div>
        </div>
      </div>

      {error ? (
        <div
          className="mb-5 flex flex-col gap-3 rounded-xl border p-4 sm:flex-row sm:items-center sm:justify-between"
          style={{ borderColor: "rgba(239, 68, 68, 0.28)", backgroundColor: "rgba(239, 68, 68, 0.06)" }}
        >
          <div className="flex items-start gap-2 text-sm" style={{ color: "rgb(var(--red-6))" }}>
            <IconExclamationCircle className="mt-0.5 shrink-0" />
            <span>{error}</span>
          </div>
          <Button size="small" onClick={() => setReloadToken((value) => value + 1)}>重新加载员工档案</Button>
        </div>
      ) : null}

      <div className="metric-grid grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4">
        <ReferenceMetric title="内置参考条目" value={benefitPolicies.length} suffix="项" caption="不是已匹配政策数量" />
        <ReferenceMetric title="官方来源链接" value={model.sourceCount} suffix="个" caption="办理前应逐项打开复核" />
        <ReferenceMetric title="含公开金额示例" value={model.amountExampleCount} suffix="项" caption="不合计为公司机会金额" tone="warning" />
        <ReferenceMetric
          title="员工字段覆盖"
          value={loading ? "加载中" : `${model.fieldCoverage}%`}
          caption="真实档案字段覆盖，不是政策准备度"
          tone="primary"
        />
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[0.96fr_1.04fr]">
        <Card style={{ borderRadius: 14 }} title="内置政策参考">
          <div className="mb-3 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
            右侧标签描述参考条目的核对状态，不是企业办理进度。
          </div>
          <div className="space-y-3">
            {benefitPolicies.map((policy) => {
              const meta = statusMeta[policy.referenceStatus];
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
                    <span className="mt-1 block truncate text-xs" style={{ color: "var(--text-color-3)" }}>{policy.category} · 静态参考</span>
                  </span>
                  <Tag color={meta.color}>{meta.label}</Tag>
                  <IconRight style={{ color: "var(--text-color-4)" }} />
                </button>
              );
            })}
          </div>
        </Card>

        <Card style={{ borderRadius: 14 }} title="参考详情与人工核对">
          <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-xl" style={{ color: "var(--color-primary)" }}>{selectedPolicy.icon}</span>
                  <h3 className="m-0 text-lg font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.title}</h3>
                </div>
                <div className="mt-2 text-sm" style={{ color: "var(--text-color-3)" }}>{selectedPolicy.description}</div>
              </div>
              <Tag color={statusMeta[selectedPolicy.referenceStatus].color}>{statusMeta[selectedPolicy.referenceStatus].label}</Tag>
            </div>
            <div className="bi-segment-grid mt-4 grid grid-cols-1 md:grid-cols-3">
              <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>建议核对角色</div>
                <div className="mt-1 font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.owner}</div>
              </div>
              <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>快照记录周期</div>
                <div className="mt-1 font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.period}</div>
              </div>
              <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>公开金额示例</div>
                <div className="mt-1 font-semibold" style={{ color: "var(--text-color-1)" }}>
                  {selectedPolicy.publicAmountExample !== null
                    ? `${formatAmount(selectedPolicy.publicAmountExample)}（非可领取金额）`
                    : "无固定金额示例，按官方核定"}
                </div>
              </div>
            </div>
            <div className="mt-4 rounded-lg px-3 py-2 text-sm leading-6" style={{ backgroundColor: "var(--color-fill-1)", color: "var(--text-color-2)" }}>
              <span className="font-medium">人工初筛提示：</span>{selectedPolicy.matchLogic}
            </div>
          </div>

          <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div>
              <div className="mb-3 font-medium">可能需要核对的数据</div>
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
              <div className="mb-3 font-medium">常见材料参考</div>
              <div className="space-y-2">
                {selectedPolicy.materials.map((item) => (
                  <div key={item} className="flex items-start gap-2 text-sm" style={{ color: "var(--text-color-2)" }}>
                    <IconFile className="mt-0.5 shrink-0" style={{ color: "var(--color-primary)" }} />
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
              <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>页面快照整理于 {REFERENCE_SNAPSHOT_UPDATED_AT}</div>
            </div>
            <Button type="outline" onClick={() => window.open(selectedPolicy.sourceUrl, "_blank", "noopener,noreferrer")}>
              打开官方来源复核
            </Button>
          </div>

          <div className="mt-3 flex items-start gap-2 rounded-2xl border p-3 text-xs leading-5" style={{ borderColor: "var(--color-warning-border)", backgroundColor: "var(--color-warning-soft)", color: "var(--color-warning)" }}>
            <IconExclamationCircle className="mt-0.5 shrink-0" />
            <span>系统没有申报、审批或到账记录；请根据公司所在地、人员身份、申报时点和官方最新材料要求人工确认。</span>
          </div>
        </Card>
      </div>

      <Card className="mt-5" style={{ borderRadius: 14 }} title="员工档案字段覆盖（可验证数据）">
        {loading ? (
          <Skeleton />
        ) : employees.length === 0 ? (
          <Empty description="暂无在职员工" />
        ) : (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-5">
            {model.employeeDataFields.map((field) => (
              <div key={field.label} className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="mb-3 flex items-center justify-between gap-3">
                  <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{field.label}</div>
                  {field.supported ? (
                    <Tag color={field.count === employees.length ? "green" : "orange"}>{field.count}/{employees.length} 已填</Tag>
                  ) : (
                    <Tag color="gray">系统未采集</Tag>
                  )}
                </div>
                <div className="text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{field.detail}</div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}

function ReferenceMetric({
  title,
  value,
  suffix,
  caption,
  tone = "default",
}: {
  title: string;
  value: number | string;
  suffix?: string;
  caption: string;
  tone?: "default" | "primary" | "warning";
}) {
  const valueColor = tone === "primary"
    ? "var(--color-primary)"
    : tone === "warning"
      ? "var(--color-warning)"
      : "var(--text-color-1)";
  return (
    <Card className="metric-card" style={{ borderRadius: 14, minHeight: 126 }}>
      <div className="text-sm" style={{ color: "var(--text-color-3)" }}>{title}</div>
      <div className="mt-3 text-2xl font-bold" style={{ color: valueColor }}>
        {value}{suffix ? <span className="ml-1 text-sm font-medium">{suffix}</span> : null}
      </div>
      <div className="mt-2 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{caption}</div>
    </Card>
  );
}
