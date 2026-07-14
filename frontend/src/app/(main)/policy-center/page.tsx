"use client";

import { useMemo, useState } from "react";
import { Button, Card, Empty, Input, Select, Tag } from "@arco-design/web-react";
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
type ConclusionTone = "primary" | "success" | "warning" | "danger" | "neutral";

type PolicyConclusion = {
  label: string;
  value: string;
  helper: string;
  tone: ConclusionTone;
};

type PolicyRule = {
  item: string;
  baseRange: string;
  companyRate: string;
  personalRate: string;
  effectivePeriod: string;
  note: string;
};

type PolicySource = {
  name: string;
  url: string;
};

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
  sourceLinks?: PolicySource[];
  conclusions?: PolicyConclusion[];
  rules?: PolicyRule[];
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
  active: { label: "需按日期复核", color: "arcoblue" },
  watch: { label: "优先复核", color: "orange" },
  expired: { label: "快照已过期", color: "gray" },
  draft: { label: "尚未核验", color: "gray" },
};

const POLICY_SNAPSHOT_UPDATED_AT = "2026-07-14";

const conclusionToneMeta: Record<ConclusionTone, { color: string; background: string; border: string }> = {
  primary: { color: "var(--color-primary)", background: "rgba(99, 102, 241, 0.08)", border: "rgba(99, 102, 241, 0.26)" },
  success: { color: "var(--color-success)", background: "rgba(16, 185, 129, 0.08)", border: "rgba(16, 185, 129, 0.24)" },
  warning: { color: "var(--color-warning)", background: "var(--color-warning-soft)", border: "var(--color-warning-border)" },
  danger: { color: "rgb(var(--red-6))", background: "rgba(239, 68, 68, 0.08)", border: "rgba(239, 68, 68, 0.24)" },
  neutral: { color: "var(--text-color-2)", background: "var(--color-fill-1)", border: "var(--border-color-light)" },
};

const policies: PolicyRecord[] = [
  {
    key: "sz-tax-startup-lite-2026",
    title: "深圳初创公司轻税务合规画像",
    scope: "tax",
    region: "中国/广东省/深圳市",
    department: "国家税务总局 / 深圳市税务局",
    status: "active",
    effectiveFrom: "2026-01-01",
    effectiveTo: "2027-12-31",
    version: "CN-GD-SZ-TAX-LITE-2026.06",
    summary: "用于初创公司增值税、企业所得税、个人所得税代扣、附加税费和印花税的申报日历、零申报、票据闭环提醒。",
    businessUse: "税务合规、票据凭证、薪酬个税、月度/季度申报待办和政策画像。",
    checklist: ["纳税人类型", "增值税纳税期限", "申报截止日", "零申报状态", "票据税期", "申报/缴款回执"],
    sourceName: "国家税务总局",
    sourceUrl: "https://fgk.chinatax.gov.cn/zcfgk/c102424/c5245729/content.html",
    sourceLinks: [
      { name: "2026 年度申报纳税期限", url: "https://fgk.chinatax.gov.cn/zcfgk/c102424/c5245729/content.html" },
      { name: "小规模月 10 万以下免征增值税", url: "https://www.chinatax.gov.cn/chinatax/n810356/n3010387/c5211011/content.html" },
      { name: "一般纳税人登记管理事项", url: "https://tianjin.chinatax.gov.cn/11200000000/0300/030004/03000418/20260104155628948.shtml" },
      { name: "个税扣缴申报累计预扣法", url: "https://www.chinatax.gov.cn/chinatax/n810341/n810760/c3959585/content.html" },
    ],
    conclusions: [
      {
        label: "2026 申报期限",
        value: "按月/季官方截止日",
        helper: "月度或季度申报按国家税务总局 2026 年通知维护，节假日月份自动顺延。",
        tone: "primary",
      },
      {
        label: "小规模免征",
        value: "月 10 万 / 季 30 万",
        helper: "2023-01-01 至 2027-12-31；固定期限纳税人年度内通常不得随意变更纳税期限。",
        tone: "success",
      },
      {
        label: "零申报",
        value: "无税款也要闭环",
        helper: "已进入税务日历的税种，即使应缴税额为 0，也需要完成申报状态和回执归档。",
        tone: "warning",
      },
      {
        label: "一般纳税人",
        value: "500 万滚动关注",
        helper: "小规模公司接近或达到连续经营期应税销售额标准时，应触发登记复核。",
        tone: "danger",
      },
    ],
    rules: [
      {
        item: "增值税",
        baseRange: "小规模月销售额 ≤ ¥100,000 / 季销售额 ≤ ¥300,000",
        companyRate: "小规模关注免征/减征；一般纳税人关注销项、进项和抵扣",
        personalRate: "不涉及个人扣缴",
        effectivePeriod: "2023-01-01 至 2027-12-31",
        note: "申报日历按纳税期限生成，特殊销售、不动产销售、差额征税等场景需要财务单独复核。",
      },
      {
        item: "企业所得税",
        baseRange: "按季度利润和应纳税所得额预缴",
        companyRate: "企业承担",
        personalRate: "不涉及个人扣缴",
        effectivePeriod: "季度预缴，年度汇算",
        note: "成本费用票据、税会差异台账、优惠政策依据和年度汇算材料需要完整归档。",
      },
      {
        item: "个人所得税代扣",
        baseRange: "工资薪金按累计预扣预缴",
        companyRate: "公司承担扣缴申报责任",
        personalRate: "员工承担税款",
        effectivePeriod: "按月扣缴申报",
        note: "工资、社保、公积金、专项附加扣除需要和薪酬模块联动，避免个税申报与发薪数据不一致。",
      },
      {
        item: "附加税费/印花税",
        baseRange: "随增值税或应税合同/凭证口径确认",
        companyRate: "按适用税费率或合同税目计算",
        personalRate: "通常不涉及个人扣缴",
        effectivePeriod: "按月、按季或按次",
        note: "附加税费依赖增值税申报结果，印花税依赖合同台账和应税凭证归集。",
      },
    ],
  },
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
    sourceLinks: [
      { name: "广东人社：2025 职工养老基数上下限", url: "https://hrss.gd.gov.cn/gkmlpt/content/4/4789/post_4789618.html" },
      { name: "广东人社/税务：2025 企业养老单位费率", url: "https://hrss.gd.gov.cn/zwgk/gsgg/content/post_4640645.html" },
      { name: "深圳医保局：2026 医保/生育基数与一档费率", url: "https://hsa.sz.gov.cn/fzlm/znts/cnyc/content/post_12568243.html" },
      { name: "深圳社保业务办理", url: "https://hrss.sz.gov.cn/szsi/zxbs/zdyw/ywjs/" },
    ],
    conclusions: [
      {
        label: "养老保险基数",
        value: "¥4,775 - ¥27,549",
        helper: "2025-07 至 2026-06；单位 16%，个人 8%；深户另有地方补充养老单位 1%。",
        tone: "primary",
      },
      {
        label: "医保/生育基数",
        value: "¥6,727 - ¥33,633",
        helper: "2026 年；一档医保单位 6%、个人 2%；生育单位 0.5%、个人不缴。",
        tone: "success",
      },
      {
        label: "失业保险基数",
        value: "¥2,520 - ¥44,265",
        helper: "2025-07 至 2026-06；单位 0.8%，个人 0.2%。",
        tone: "warning",
      },
      {
        label: "工伤保险费率",
        value: "0.2% - 1.4%",
        helper: "按行业类别和浮动费率核定，单位承担，个人不缴。",
        tone: "danger",
      },
    ],
    rules: [
      {
        item: "养老保险",
        baseRange: "¥4,775 - ¥27,549",
        companyRate: "16%（深户地方补充养老另加 1%）",
        personalRate: "8%",
        effectivePeriod: "2025-07-01 至 2026-06-30",
        note: "深圳按广东企业职工养老“其他地区”下限口径维护，员工以本人月工资核定。",
      },
      {
        item: "医疗保险一档",
        baseRange: "¥6,727 - ¥33,633",
        companyRate: "6%",
        personalRate: "2%",
        effectivePeriod: "2026-01-01 至 2026-12-31",
        note: "2026 年起企业职工基本医疗保险一档单位缴费费率恢复为 6%。",
      },
      {
        item: "医疗保险二档",
        baseRange: "¥6,727 - ¥33,633",
        companyRate: "1.5%",
        personalRate: "0.5%",
        effectivePeriod: "2026-01-01 至 2026-12-31",
        note: "人员薪酬模块按员工医保档次分别测算。",
      },
      {
        item: "生育保险",
        baseRange: "¥6,727 - ¥33,633",
        companyRate: "0.5%",
        personalRate: "0%",
        effectivePeriod: "2026-01-01 至 2026-12-31",
        note: "随职工基本医疗保险基数维护，由单位承担。",
      },
      {
        item: "失业保险",
        baseRange: "¥2,520 - ¥44,265",
        companyRate: "0.8%",
        personalRate: "0.2%",
        effectivePeriod: "2025-07-01 至 2026-06-30",
        note: "上下限随深圳最低工资和上年度平均工资口径调整。",
      },
      {
        item: "工伤保险",
        baseRange: "不低于 ¥2,520；按申报工资和行业核定",
        companyRate: "0.2% - 1.4%",
        personalRate: "0%",
        effectivePeriod: "2024-07-01 起",
        note: "八类行业基准费率，实际以单位行业类别和浮动费率为准。",
      },
    ],
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
    conclusions: [
      {
        label: "办理顺序",
        value: "先社保核定，再税务缴费",
        helper: "补缴或补差通常先由社保经办核定金额，再进入税务申报缴费环节。",
        tone: "primary",
      },
      {
        label: "核心场景",
        value: "漏参保/补差/补登记",
        helper: "覆盖入职漏参保、历史基数补差、2024-07 后未参保补登记等场景。",
        tone: "warning",
      },
      {
        label: "关键证据",
        value: "劳动关系 + 工资依据",
        helper: "合同、工资流水、个税记录、就业登记和申请表需要成套归档。",
        tone: "success",
      },
    ],
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
    sourceLinks: [
      { name: "深圳政府在线：2025-07 至 2026-06 缴存基数规则", url: "https://www.sz.gov.cn/ztfw/zfly/wyw_184911/ywzsk_184570/content/post_12526734.html" },
      { name: "深圳政府在线：比例调整范围", url: "https://www.sz.gov.cn/hdjl/ywzsk/jsj/zfgjj/content/post_12562346.html" },
      { name: "深圳住建局：个人比例调整注意事项", url: "https://zjj.sz.gov.cn/szszfhjsjwzgkml/szszfhjsjwzgkml/seztfw/zfly/wyw/ywzsk/content/post_12752447.html" },
    ],
    conclusions: [
      {
        label: "缴存基数",
        value: "¥2,520 - ¥44,265",
        helper: "2025-07 至 2026-06 演示口径；下限为深圳最低工资，上限为 2024 年全市在岗职工月平均工资 3 倍。",
        tone: "primary",
      },
      {
        label: "单位比例",
        value: "5% - 12%",
        helper: "在一个公积金年度内通常只能调整一次，取 1% 的整数倍。",
        tone: "success",
      },
      {
        label: "个人比例",
        value: "5% - 12%",
        helper: "个人比例不得低于单位比例，且不得高于 12%。",
        tone: "success",
      },
      {
        label: "调整年度",
        value: "7/1 - 次年 6/30",
        helper: "基数和比例都按住房公积金年度管理，需要保留员工确认记录。",
        tone: "warning",
      },
    ],
    rules: [
      {
        item: "住房公积金基数",
        baseRange: "¥2,520 - ¥44,265",
        companyRate: "按单位选定比例 5% - 12%",
        personalRate: "5% - 12%，个人不低于单位",
        effectivePeriod: "2025-07-01 至 2026-06-30",
        note: "缴存基数为职工本人上一年度月平均工资；新入职/调入员工按官方规则沿用当前基数。",
      },
      {
        item: "比例调整",
        baseRange: "不涉及基数",
        companyRate: "5% - 12%，取 1% 整数倍",
        personalRate: "5% - 12%，个人可按规则高于单位",
        effectivePeriod: "每个公积金年度一次",
        note: "单位下调比例时需同步确认个人比例是否调整，系统应保留员工确认记录。",
      },
    ],
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
    conclusions: [
      {
        label: "适用重点",
        value: "培训项目 + 证书材料",
        helper: "需要将培训计划、人员清单、证书或评价证明和补贴申请材料闭环归档。",
        tone: "primary",
      },
    ],
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
    conclusions: [
      {
        label: "匹配维度",
        value: "毕业年份 + 社保记录",
        helper: "需要先做员工政策画像，再判断是否进入就业补贴、社保补贴或创业扶持流程。",
        tone: "warning",
      },
    ],
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
    conclusions: [
      {
        label: "补贴口径",
        value: "¥3,000/户/年",
        helper: "最长不超过 2 年，重点核验孵化关系、园区资质和服务记录。",
        tone: "success",
      },
    ],
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
  const initialPolicy = policies.find((policy) => initialScope !== "all" && policy.scope === initialScope) || policies[0];
  const [scope, setScope] = useState<PolicyScope>(scopeLabels[initialScope] ? initialScope : "all");
  const [keyword, setKeyword] = useState("");
  const [selectedPolicyKey, setSelectedPolicyKey] = useState(initialPolicy.key);

  const filteredPolicies = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    return policies.filter((policy) => {
      const conclusions = (policy.conclusions || []).map((item) => `${item.label} ${item.value} ${item.helper}`).join(" ");
      const rules = (policy.rules || []).map((item) => `${item.item} ${item.baseRange} ${item.companyRate} ${item.personalRate} ${item.note}`).join(" ");
      const searchable =
        `${policy.title} ${policy.summary} ${policy.department} ${policy.region} ${policy.businessUse} ${conclusions} ${rules}`.toLowerCase();
      if (scope !== "all" && policy.scope !== scope) return false;
      if (normalizedKeyword && !searchable.includes(normalizedKeyword)) return false;
      return true;
    });
  }, [keyword, scope]);

  const selectedPolicy =
    filteredPolicies.find((policy) => policy.key === selectedPolicyKey) ||
    filteredPolicies[0] ||
    null;

  const sourceCount = new Set(policies.flatMap((policy) => [
    policy.sourceUrl,
    ...(policy.sourceLinks || []).map((source) => source.url),
  ])).size;
  const reviewCount = policies.filter((policy) => policy.status !== "active").length;

  return (
    <div className="mx-auto max-w-[1600px] animate-fade-in">
      <PageHeader
        title="政策参考中心"
        subtitle="深圳地区内置参考快照；用于查找官方来源，不是自动同步或资格认定系统"
        icon={<IconSettings />}
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
              <span className="font-semibold" style={{ color: "var(--text-color-1)" }}>内置参考快照 · 需打开官方来源复核</span>
              <Tag color="orange">整理时间 {POLICY_SNAPSHOT_UPDATED_AT}</Tag>
            </div>
            <div className="mt-1 text-sm leading-6" style={{ color: "var(--text-color-2)" }}>
              本页内容随前端代码发布，不会自动追踪政策变更，也没有申报、审批或办理进度。
              当前条目仅覆盖中国广东省深圳市；适用条件、期限、金额和材料以官方最新通知及经办审核为准。
            </div>
          </div>
        </div>
      </div>

      <div className="metric-grid grid grid-cols-1 md:grid-cols-4">
        <Card className="metric-card" style={{ borderRadius: 12 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>内置参考条目</div>
          <div className="mt-3 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{policies.length}</div>
          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>不代表已匹配或可办理数量</div>
        </Card>
        <Card className="metric-card" style={{ borderRadius: 12 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>适用范围</div>
          <div className="mt-3 text-xl font-bold" style={{ color: "var(--text-color-1)" }}>广东 · 深圳</div>
          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>未覆盖其他地区</div>
        </Card>
        <Card className="metric-card" style={{ borderRadius: 12 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>官方来源链接</div>
          <div className="mt-3 text-2xl font-bold" style={{ color: "var(--color-primary)" }}>{sourceCount}</div>
          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>需逐项打开确认最新口径</div>
        </Card>
        <Card className="metric-card" style={{ borderRadius: 12 }}>
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>优先复核条目</div>
          <div className="mt-3 text-2xl font-bold" style={{ color: "var(--color-warning)" }}>{reviewCount}</div>
          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>来自快照标记，不是办理待办</div>
        </Card>
      </div>

      <Card className="filter-card mb-4" style={{ borderRadius: 12 }}>
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[240px_minmax(280px,1fr)]">
          <Select value={scope} onChange={(value) => setScope(value as PolicyScope)} style={{ width: "100%" }}>
            {Object.entries(scopeLabels).map(([value, label]) => (
              <Select.Option key={value} value={value}>{label}</Select.Option>
            ))}
          </Select>
          <Input
            aria-label="搜索政策参考快照"
            allowClear
            prefix={<IconSearch />}
            placeholder="搜索政策名称、适用模块、官方部门..."
            value={keyword}
            onChange={setKeyword}
          />
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(300px,380px)_minmax(0,1fr)] 2xl:grid-cols-[minmax(340px,420px)_minmax(0,1fr)]">
        <Card className="min-w-0" style={{ borderRadius: 12 }} title="政策版本列表">
          <div className="space-y-3">
            {filteredPolicies.map((policy) => {
              const selected = selectedPolicy?.key === policy.key;
              const meta = statusMeta[policy.status];
              return (
                <button
                  key={policy.key}
                  type="button"
                  onClick={() => setSelectedPolicyKey(policy.key)}
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
            {filteredPolicies.length === 0 ? (
              <Empty description="当前筛选下没有参考条目，请调整范围或关键词" />
            ) : null}
          </div>
        </Card>

        <Card className="min-w-0" style={{ borderRadius: 12 }} title="政策详情">
          {selectedPolicy ? (
            <>
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
            <div className="bi-segment-grid mt-4 grid grid-cols-1 md:grid-cols-[repeat(3,minmax(0,1fr))]">
              <div className="min-w-0 rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>政策类型</div>
                <div className="mt-1 break-words font-semibold" style={{ color: "var(--text-color-1)" }}>{scopeLabels[selectedPolicy.scope]}</div>
              </div>
              <div className="min-w-0 rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>适用地区</div>
                <div className="mt-1 break-words font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.region}</div>
              </div>
              <div className="min-w-0 rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>版本</div>
                <div className="mt-1 break-words font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.version}</div>
              </div>
            </div>
            <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>快照记录的适用时间</div>
                <div className="mt-1 font-medium" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.effectiveFrom} - {selectedPolicy.effectiveTo}</div>
              </div>
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>官方部门</div>
                <div className="mt-1 font-medium" style={{ color: "var(--text-color-1)" }}>{selectedPolicy.department}</div>
              </div>
            </div>
          </div>

          {selectedPolicy.conclusions?.length ? (
            <div className="mt-4">
              <div className="mb-3">
                <div className="font-medium" style={{ color: "var(--text-color-1)" }}>快照摘录与复核要点</div>
                <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>以下内容不是针对当前公司的自动计算结果。</div>
              </div>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-[repeat(2,minmax(0,1fr))] 2xl:grid-cols-[repeat(4,minmax(0,1fr))]">
                {selectedPolicy.conclusions.map((conclusion) => {
                  const tone = conclusionToneMeta[conclusion.tone];
                  return (
                    <div
                      key={`${selectedPolicy.key}-${conclusion.label}`}
                      className="min-w-0 rounded-xl border p-4"
                      style={{ borderColor: tone.border, backgroundColor: tone.background }}
                    >
                      <div className="text-xs font-medium" style={{ color: "var(--text-color-3)" }}>{conclusion.label}</div>
                      <div className="mt-2 break-words text-lg font-bold" style={{ color: tone.color }}>{conclusion.value}</div>
                      <div className="mt-2 text-xs leading-5" style={{ color: "var(--text-color-2)" }}>{conclusion.helper}</div>
                    </div>
                  );
                })}
              </div>
            </div>
          ) : null}

          {selectedPolicy.rules?.length ? (
            <div className="mt-4">
              <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
                <div className="font-medium" style={{ color: "var(--text-color-1)" }}>快照记录的缴费/计算口径</div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>办理或核算前必须以官方最新口径复核</div>
              </div>
              <div className="bi-flat-list">
                {selectedPolicy.rules.map((rule) => (
                  <div
                    key={`${selectedPolicy.key}-${rule.item}`}
                    className="rounded-xl border p-4"
                    style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="text-base font-semibold" style={{ color: "var(--text-color-1)" }}>{rule.item}</div>
                      <Tag color="arcoblue">{rule.effectivePeriod}</Tag>
                    </div>
                    <div className="bi-segment-grid mt-3 grid grid-cols-1 sm:grid-cols-[repeat(2,minmax(0,1fr))] 2xl:grid-cols-[repeat(4,minmax(0,1fr))]">
                      {[
                        { label: "基数上下限", value: rule.baseRange },
                        { label: "公司承担比例", value: rule.companyRate },
                        { label: "个人扣缴比例", value: rule.personalRate },
                        { label: "执行周期", value: rule.effectivePeriod },
                      ].map((field) => (
                        <div
                          key={`${rule.item}-${field.label}`}
                          className="min-w-0 rounded-lg border px-3 py-2"
                          style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-bg-2)" }}
                        >
                          <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{field.label}</div>
                          <div className="mt-1 break-words text-sm font-semibold leading-5" style={{ color: "var(--text-color-1)" }}>
                            {field.value}
                          </div>
                        </div>
                      ))}
                    </div>
                    <div className="mt-3 rounded-lg px-3 py-2 text-xs leading-5" style={{ backgroundColor: "var(--color-fill-1)", color: "var(--text-color-3)" }}>
                      {rule.note}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

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
              <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>页面快照整理于 {POLICY_SNAPSHOT_UPDATED_AT}</div>
            </div>
            <Button type="outline" onClick={() => window.open(selectedPolicy.sourceUrl, "_blank", "noopener,noreferrer")}>
              打开官方来源复核
            </Button>
          </div>

          {selectedPolicy.sourceLinks?.length ? (
            <div className="mt-3 rounded-xl border p-3" style={{ borderColor: "var(--border-color-light)" }}>
              <div className="mb-2 text-xs" style={{ color: "var(--text-color-3)" }}>结论引用来源</div>
              <div className="flex flex-wrap gap-2">
                {selectedPolicy.sourceLinks.map((source) => (
                  <Button
                    key={source.url}
                    size="small"
                    type="outline"
                    onClick={() => window.open(source.url, "_blank", "noopener,noreferrer")}
                  >
                    {source.name}
                  </Button>
                ))}
              </div>
            </div>
          ) : null}

          <div className="mt-4 flex items-start gap-2 rounded-2xl border p-3 text-xs leading-5" style={{ borderColor: "var(--color-warning-border)", backgroundColor: "var(--color-warning-soft)", color: "var(--color-warning)" }}>
            <IconExclamationCircle className="mt-0.5 shrink-0" />
            <span>这是内置参考快照，不是法律、税务或人事意见，也不代表系统正在跟踪或办理。最终适用条件、金额、期限和材料以官方最新通知及经办审核结果为准。</span>
          </div>
            </>
          ) : (
            <Empty description="请选择一个可见的参考条目" />
          )}
        </Card>
      </div>
    </div>
  );
}
