"use client";

import { useMemo, useState } from "react";
import { Button, Card, Input, Progress, Select, Tag } from "@arco-design/web-react";
import {
  IconCalendar,
  IconCheckCircle,
  IconExclamationCircle,
  IconFile,
  IconIdcard,
  IconRight,
  IconSafe,
  IconSearch,
  IconSettings,
  IconTrophy,
} from "@arco-design/web-react/icon";
import { useSearchParams } from "next/navigation";
import PageHeader from "@/components/common/PageHeader";

type PolicyScope = "all" | "social-insurance" | "housing-fund" | "talent" | "tax" | "employment";
type PolicyStatus = "active" | "watch" | "expired" | "draft";

type PolicyRecord = {
  key: string;
  title: string;
  scope: PolicyScope;
  region: string;
  department: string;
  status: PolicyStatus;
  effectiveFrom: string;
  effectiveTo: string;
  version: string;
  summary: string;
  businessUse: string;
  checklist: string[];
  sourceName: string;
  sourceUrl: string;
};

const scopeLabels: Record<PolicyScope, string> = {
  all: "全部政策",
  "social-insurance": "社保",
  "housing-fund": "公积金",
  talent: "人才福利",
  tax: "税务",
  employment: "就业创业",
};

const statusMeta: Record<PolicyStatus, { label: string; color: string }> = {
  active: { label: "生效中", color: "green" },
  watch: { label: "需关注", color: "orange" },
  expired: { label: "已过期", color: "gray" },
  draft: { label: "待核验", color: "arcoblue" },
};

const policies: PolicyRecord[] = [
  {
    key: "sz-social-base-2026",
    title: "深圳职工社保缴费基数与险种口径",
    scope: "social-insurance",
    region: "中国/广东省/深圳市",
    department: "深圳市社会保险基金管理局",
    status: "active",
    effectiveFrom: "2025-07",
    effectiveTo: "2026-06",
    version: "SZ-SI-2026.06",
    summary: "用于养老、医疗、生育、失业、工伤的基数上下限、单位/个人比例和员工薪酬测算。",
    businessUse: "人员薪酬、发薪测算、公司月人力成本、入离职参保检查。",
    checklist: ["员工参保地区", "户籍类型", "医保档次", "各险种基数", "单位/个人比例"],
    sourceName: "深圳市社会保险基金管理局",
    sourceUrl: "https://hrss.sz.gov.cn/szsi/zxbs/zdyw/ywjs/",
  },
  {
    key: "sz-social-backpay-2026",
    title: "深圳社保补缴/补登记业务规则",
    scope: "social-insurance",
    region: "中国/广东省/深圳市",
    department: "深圳市社会保险基金管理局",
    status: "active",
    effectiveFrom: "2024-07",
    effectiveTo: "长期关注",
    version: "SZ-SI-BACKPAY-2026.06",
    summary: "覆盖两年内补缴、补差、2024 年 7 月后补登记、税务缴费和材料清单。",
    businessUse: "入职漏参保、离职停保异常、历史基数补差、劳动关系材料归档。",
    checklist: ["补缴所属期", "劳动合同", "工资流水", "个税记录", "补缴/补登记申请表", "经办人证件"],
    sourceName: "深圳市社会保险基金管理局",
    sourceUrl: "https://hrss.sz.gov.cn/szsi/zxbs/zdyw/gpywcj/qyzgdjbjsb/",
  },
  {
    key: "sz-housing-fund-ratio-2026",
    title: "深圳住房公积金缴存比例校验",
    scope: "housing-fund",
    region: "中国/广东省/深圳市",
    department: "深圳市住房公积金管理中心",
    status: "active",
    effectiveFrom: "2026-04-01",
    effectiveTo: "长期关注",
    version: "SZ-HF-2026.04",
    summary: "用于校验个人缴存比例、单位缴存比例和公积金缴存基数规则。",
    businessUse: "人员薪酬、公积金月缴、员工福利一致性校验。",
    checklist: ["公积金基数", "个人比例", "单位比例", "缴存年月", "员工确认"],
    sourceName: "深圳政府在线",
    sourceUrl: "https://www.sz.gov.cn/hdjl/ywzsk/jsj/zfgjj/content/post_12703676.html",
  },
  {
    key: "sz-skill-training-2026",
    title: "深圳职业技能培训补贴",
    scope: "talent",
    region: "中国/广东省/深圳市",
    department: "深圳市人力资源和社会保障局",
    status: "active",
    effectiveFrom: "2026-01",
    effectiveTo: "2026-12-31",
    version: "SZ-TALENT-SKILL-2026.12",
    summary: "适用于劳动者、企业、机构等主体的职业技能培训补贴政策跟踪。",
    businessUse: "人才福利、员工培训计划、部门技能提升预算。",
    checklist: ["培训计划", "参训人员", "培训项目", "证书/评价证明", "申请材料"],
    sourceName: "深圳市人力资源和社会保障局",
    sourceUrl: "https://hrss.sz.gov.cn/zmhd/cjwt/cjwt/rsrc/content/post_12647175.html",
  },
  {
    key: "sz-graduate-employment-2025",
    title: "深圳高校毕业生就业创业扶持",
    scope: "employment",
    region: "中国/广东省/深圳市",
    department: "深圳市人力资源和社会保障局",
    status: "watch",
    effectiveFrom: "2025",
    effectiveTo: "以最新年度清单为准",
    version: "SZ-EMP-GRAD-2025",
    summary: "用于匹配高校毕业生、创业人员、灵活就业社保补贴等政策机会。",
    businessUse: "人才福利、员工政策画像、初创企业补贴机会。",
    checklist: ["毕业年份", "学历/院校", "社保缴纳记录", "工商登记信息", "就业登记信息"],
    sourceName: "深圳市人力资源和社会保障局",
    sourceUrl: "https://hrss.sz.gov.cn/ztfw/yshj/jyzcyjd/content/post_12317950.html",
  },
  {
    key: "sz-startup-incubation-2026",
    title: "深圳创业孵化补贴",
    scope: "employment",
    region: "中国/广东省/深圳市",
    department: "深圳市人力资源和社会保障局",
    status: "watch",
    effectiveFrom: "2026-04",
    effectiveTo: "按最新通知",
    version: "SZ-INCUBATION-2026.04",
    summary: "每年每户 3000 元、最长不超过 2 年的创业孵化补贴口径，重点关注园区/孵化关系。",
    businessUse: "人才福利、创业扶持、园区政策资料归档。",
    checklist: ["孵化基地资质", "入孵协议", "工商登记", "孵化服务记录", "申请期限"],
    sourceName: "深圳市人力资源和社会保障局",
    sourceUrl: "https://hrss.sz.gov.cn/ztfw/cjjy/cyfw/cybt/content/post_10278721.html",
  },
];

const scopeIcon: Record<PolicyScope, React.ReactNode> = {
  all: <IconSearch />,
  "social-insurance": <IconSafe />,
  "housing-fund": <IconIdcard />,
  talent: <IconTrophy />,
  tax: <IconFile />,
  employment: <IconCalendar />,
};

export default function PolicyCenterPage() {
  const searchParams = useSearchParams();
  const initialScope = (searchParams.get("scope") || "all") as PolicyScope;
  const [scope, setScope] = useState<PolicyScope>(scopeLabels[initialScope] ? initialScope : "all");
  const [keyword, setKeyword] = useState("");
  const [selectedPolicy, setSelectedPolicy] = useState<PolicyRecord>(policies[0]);

  const filteredPolicies = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    return policies.filter((policy) => {
      const searchable = `${policy.title} ${policy.summary} ${policy.department} ${policy.region} ${policy.businessUse}`.toLowerCase();
      if (scope !== "all" && policy.scope !== scope) return false;
      if (normalizedKeyword && !searchable.includes(normalizedKeyword)) return false;
      return true;
    });
  }, [keyword, scope]);

  const activeCount = policies.filter((policy) => policy.status === "active").length;
  const watchCount = policies.filter((policy) => policy.status === "watch").length;
  const readiness = Math.round((activeCount / policies.length) * 100);

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="政策中心"
        subtitle="多地区政策版本、官方来源、适用模块和资料清单集中管理"
        icon={<IconSettings />}
        extra={
          <Button type="primary" icon={<IconCheckCircle />} disabled>
            政策同步
          </Button>
        }
      />

      <div className="mb-6 grid grid-cols-1 gap-4 md:grid-cols-4">
        <Card style={{ borderRadius: 12 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>政策版本</div>
          <div className="mt-3 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{policies.length}</div>
          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>深圳政策包</div>
        </Card>
        <Card style={{ borderRadius: 12 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>生效中</div>
          <div className="mt-3 text-2xl font-bold" style={{ color: "var(--color-success)" }}>{activeCount}</div>
          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>可用于模块规则</div>
        </Card>
        <Card style={{ borderRadius: 12 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>需关注</div>
          <div className="mt-3 text-2xl font-bold" style={{ color: "var(--color-warning)" }}>{watchCount}</div>
          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>等待年度更新或资格确认</div>
        </Card>
        <Card style={{ borderRadius: 12 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>可用度</div>
          <div className="mt-3 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{readiness}%</div>
          <Progress percent={readiness} showText={false} color="var(--color-primary)" className="mt-3" />
        </Card>
      </div>

      <Card className="mb-4" style={{ borderRadius: 12 }}>
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[240px_minmax(280px,1fr)]">
          <Select value={scope} onChange={(value) => setScope(value as PolicyScope)} style={{ width: "100%" }}>
            {Object.entries(scopeLabels).map(([value, label]) => (
              <Select.Option key={value} value={value}>{label}</Select.Option>
            ))}
          </Select>
          <Input
            allowClear
            prefix={<IconSearch />}
            placeholder="搜索政策名称、适用模块、官方部门..."
            value={keyword}
            onChange={setKeyword}
          />
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[0.9fr_1.1fr]">
        <Card style={{ borderRadius: 12 }} title="政策版本列表">
          <div className="space-y-3">
            {filteredPolicies.map((policy) => {
              const selected = selectedPolicy.key === policy.key;
              const meta = statusMeta[policy.status];
              return (
                <button
                  key={policy.key}
                  type="button"
                  onClick={() => setSelectedPolicy(policy)}
                  className="flex w-full cursor-pointer items-center gap-3 rounded-xl border bg-transparent p-3 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                  style={{
                    borderColor: selected ? "rgba(99, 102, 241, 0.42)" : "var(--border-color-light)",
                    backgroundColor: selected ? "rgba(99, 102, 241, 0.08)" : "transparent",
                  }}
                >
                  <span
                    className="grid h-10 w-10 shrink-0 place-items-center rounded-xl"
                    style={{ backgroundColor: "var(--color-fill-1)", color: selected ? "var(--color-primary)" : "var(--text-color-3)" }}
                  >
                    {scopeIcon[policy.scope]}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold" style={{ color: "var(--text-color-1)" }}>{policy.title}</span>
                    <span className="mt-1 block truncate text-xs" style={{ color: "var(--text-color-3)" }}>{policy.region} · {policy.version}</span>
                  </span>
                  <Tag color={meta.color}>{meta.label}</Tag>
                  <IconRight style={{ color: "var(--text-color-4)" }} />
                </button>
              );
            })}
          </div>
        </Card>

        <Card style={{ borderRadius: 12 }} title="政策详情">
          <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex min-w-0 items-center gap-2">
                  <span className="text-xl" style={{ color: "var(--color-primary)" }}>{scopeIcon[selectedPolicy.scope]}</span>
                  <h3 className="m-0 truncate text-lg font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.title}</h3>
                </div>
                <div className="mt-2 text-sm leading-6" style={{ color: "var(--text-color-3)" }}>{selectedPolicy.summary}</div>
              </div>
              <Tag color={statusMeta[selectedPolicy.status].color}>{statusMeta[selectedPolicy.status].label}</Tag>
            </div>
            <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-3">
              <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>政策类型</div>
                <div className="mt-1 font-semibold" style={{ color: "var(--text-color-1)" }}>{scopeLabels[selectedPolicy.scope]}</div>
              </div>
              <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>适用地区</div>
                <div className="mt-1 font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.region}</div>
              </div>
              <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>版本</div>
                <div className="mt-1 font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.version}</div>
              </div>
            </div>
            <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>生效时间</div>
                <div className="mt-1 font-medium" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.effectiveFrom} - {selectedPolicy.effectiveTo}</div>
              </div>
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>官方部门</div>
                <div className="mt-1 font-medium" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.department}</div>
              </div>
            </div>
          </div>

          <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div>
              <div className="mb-3 font-medium">适用模块</div>
              <div className="rounded-xl border p-3 text-sm leading-6" style={{ borderColor: "var(--border-color-light)", color: "var(--text-color-2)" }}>
                {selectedPolicy.businessUse}
              </div>
            </div>
            <div>
              <div className="mb-3 font-medium">规则/材料字段</div>
              <div className="space-y-2">
                {selectedPolicy.checklist.map((item) => (
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

          <div className="mt-4 flex items-start gap-2 rounded-xl border p-3 text-xs leading-5" style={{ borderColor: "rgba(245, 158, 11, 0.32)", color: "var(--color-warning)" }}>
            <IconExclamationCircle className="mt-0.5 shrink-0" />
            <span>政策中心仅做内部管理与提醒，最终申报条件、金额和材料以官方最新通知及经办审核结果为准。</span>
          </div>
        </Card>
      </div>
    </div>
  );
}
