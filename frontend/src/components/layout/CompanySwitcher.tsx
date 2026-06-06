"use client";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Dropdown, Form, Input, Message, Modal, Select, Spin } from "@arco-design/web-react";
import { IconBranch, IconCheck, IconDown, IconLocation, IconPlus } from "@arco-design/web-react/icon";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useAppStore } from "@/lib/stores/appStore";
import type { Company } from "@/lib/types";

const FormItem = Form.Item;

interface CompanyFormValues {
  name: string;
  entityType?: "company" | "household";
  industry?: string;
  taxpayerType?: string;
  currency?: string;
  country?: string;
  province?: string;
  city?: string;
  district?: string;
}

const regionText = (company: Company) => (
  company.operatingRegion
  || [company.country, company.province, company.city, company.district].filter(Boolean).join("/")
  || "地区待完善"
);

const policyKeyFor = (values: CompanyFormValues) => {
  if (values.entityType === "household") return "CN-HOUSEHOLD-ASSET-PROFILE";
  const city = values.city || "";
  if (city.includes("深圳")) return "CN-GD-SZ-DEMO-POLICY";
  return "CN-DEFAULT-DEMO-POLICY";
};

const subjectTypeLabel = (company: Pick<Company, "entityType">) => (
  company.entityType === "household" ? "家庭主体" : "公司主体"
);

export default function CompanySwitcher() {
  const [companies, setCompanies] = useState<Company[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<CompanyFormValues>();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const setActiveCompanyId = useAppStore((state) => state.setActiveCompanyId);

  const activeCompany = useMemo(
    () => companies.find((company) => company.id === activeCompanyId) || companies[0] || null,
    [activeCompanyId, companies]
  );

  const loadCompanies = useCallback(async () => {
    try {
      setLoading(true);
      const res = await enterpriseApi.companies();
      setCompanies(res.data);

      const storedCompanyId = useAppStore.getState().activeCompanyId;
      if (res.data.length > 0 && (!storedCompanyId || !res.data.some((company) => company.id === storedCompanyId))) {
        setActiveCompanyId(res.data[0].id);
      }
    } catch {
      Message.error("公司主体加载失败");
    } finally {
      setLoading(false);
    }
  }, [setActiveCompanyId]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadCompanies();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadCompanies]);

  const openCreateModal = () => {
    form.setFieldsValue({
      name: "",
      entityType: "company",
      industry: "软件与信息技术服务",
      taxpayerType: "小规模纳税人",
      currency: "CNY",
      country: "中国",
      province: "广东省",
      city: "深圳市",
      district: "",
    });
    setModalVisible(true);
  };

  const handleCreateCompany = async (values: CompanyFormValues) => {
    try {
      setCreating(true);
      const operatingRegion = [values.country, values.province, values.city, values.district]
        .filter(Boolean)
        .join("/");
      const res = await enterpriseApi.createCompany({
        ...values,
        entityType: values.entityType || "company",
        currency: values.currency || "CNY",
        taxpayerType: values.taxpayerType || (values.entityType === "household" ? "非经营主体" : "小规模纳税人"),
        industry: values.industry || (values.entityType === "household" ? "家庭资产管理" : "未设置"),
        operatingRegion,
        policyProfileKey: policyKeyFor(values),
        fiscalYearStartMonth: 1,
      });
      setCompanies((prev) => {
        const next = prev.filter((company) => company.id !== res.data.id);
        return [...next, res.data].sort((left, right) => left.id - right.id);
      });
      setActiveCompanyId(res.data.id);
      setModalVisible(false);
      form.resetFields();
      Message.success("主体已创建并切换");
    } catch {
      Message.error("主体创建失败");
    } finally {
      setCreating(false);
    }
  };

  const droplist = (
    <div
      data-company-switcher-menu
      className="overflow-hidden rounded-xl border shadow-lg"
      style={{ width: 360, backgroundColor: "var(--bg-color-card)", borderColor: "var(--border-color)" }}
    >
      <div
        className="flex h-14 flex-col justify-center border-b px-4"
        style={{ borderColor: "var(--border-color)" }}
      >
        <div className="text-sm font-medium" style={{ color: "var(--text-color-1)" }}>主体切换</div>
        <div className="text-xs mt-1" style={{ color: "var(--text-color-3)" }}>公司和家庭资金分开管理，往来单独记录</div>
      </div>

      <div className="max-h-[264px] overflow-y-auto p-2">
        {loading ? (
          <div className="flex h-20 items-center justify-center">
            <Spin />
          </div>
        ) : companies.length === 0 ? (
          <div className="flex h-20 items-center justify-center px-3 text-center text-sm" style={{ color: "var(--text-color-3)" }}>
            暂无可访问主体
          </div>
        ) : companies.map((company) => {
          const isActive = company.id === activeCompany?.id;
          return (
            <button
              key={company.id}
              type="button"
              className="flex h-16 w-full cursor-pointer items-center gap-3 rounded-lg border-0 bg-transparent px-3 text-left transition-colors hover:bg-black/5 dark:hover:bg-white/5"
              onClick={() => setActiveCompanyId(company.id)}
            >
              <span
                className="grid h-9 w-9 shrink-0 place-items-center rounded-lg"
                style={{
                  backgroundColor: isActive ? "var(--color-primary)" : "rgba(100, 116, 139, 0.1)",
                  color: isActive ? "#fff" : "var(--text-color-3)",
                }}
              >
                <IconBranch />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>
                  {company.name}
                </span>
                <span className="mt-0.5 flex items-center gap-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                  <IconLocation />
                  {subjectTypeLabel(company)} · {regionText(company)} · {company.taxpayerType}
                </span>
              </span>
              {isActive && <IconCheck style={{ color: "var(--color-primary)" }} />}
            </button>
          );
        })}
      </div>

      <div className="flex items-center border-t px-2" style={{ height: 52, borderColor: "var(--border-color)" }}>
        <button
          type="button"
          className="flex h-9 w-full cursor-pointer items-center justify-center gap-2 rounded-lg border-0 bg-transparent px-3 text-sm font-medium transition-colors hover:bg-black/5 dark:hover:bg-white/5"
          style={{ color: "var(--color-primary)" }}
          onClick={openCreateModal}
        >
          <IconPlus />
          新增主体
        </button>
      </div>
    </div>
  );

  return (
    <>
      <Dropdown droplist={droplist} trigger="click" position="br">
        <button
          data-company-switcher-trigger
          type="button"
          className="flex h-10 w-11 cursor-pointer items-center gap-2 rounded-xl border bg-white px-2 text-left transition-colors hover:bg-black/[0.025] md:w-[280px] md:px-3 xl:w-[360px] dark:bg-transparent dark:hover:bg-white/[0.04]"
          style={{ borderColor: "var(--border-color)", color: "var(--text-color-2)" }}
        >
          <span
            className="grid h-7 w-7 shrink-0 place-items-center rounded-lg"
            style={{ backgroundColor: "rgba(99, 102, 241, 0.1)", color: "var(--color-primary)" }}
          >
            <IconBranch />
          </span>
          <span className="hidden min-w-0 md:block">
            <span className="block truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>
              {loading ? "加载公司主体..." : activeCompany?.name || "选择公司主体"}
            </span>
            {activeCompany && (
              <span className="hidden truncate text-xs xl:block" style={{ color: "var(--text-color-3)" }}>
                {subjectTypeLabel(activeCompany)} · {regionText(activeCompany)}
              </span>
            )}
          </span>
          <IconDown className="hidden shrink-0 md:block" style={{ color: "var(--text-color-3)" }} />
        </button>
      </Dropdown>

      <Modal
        title="新增主体"
        visible={modalVisible}
        confirmLoading={creating}
        onCancel={() => setModalVisible(false)}
        onOk={() => form.submit()}
        style={{ width: 640 }}
      >
        <Form form={form} layout="vertical" onSubmit={handleCreateCompany}>
          <FormItem label="主体类型" field="entityType" rules={[{ required: true, message: "请选择主体类型" }]}>
            <Select>
              <Select.Option value="company">公司主体</Select.Option>
              <Select.Option value="household">家庭主体</Select.Option>
            </Select>
          </FormItem>
          <FormItem label="主体名称" field="name" rules={[{ required: true, message: "请输入主体名称" }]}>
            <Input placeholder="例如：广州某某贸易有限公司 / 家庭资产主体" />
          </FormItem>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <FormItem label="行业" field="industry" rules={[{ required: true, message: "请输入行业" }]}>
              <Input placeholder="软件与信息技术服务" />
            </FormItem>
            <FormItem label="纳税人类型" field="taxpayerType" rules={[{ required: true, message: "请选择纳税人类型" }]}>
              <Select>
                <Select.Option value="小规模纳税人">小规模纳税人</Select.Option>
                <Select.Option value="一般纳税人">一般纳税人</Select.Option>
              </Select>
            </FormItem>
            <FormItem label="国家/地区" field="country" rules={[{ required: true, message: "请输入国家或地区" }]}>
              <Input placeholder="中国" />
            </FormItem>
            <FormItem label="省/州" field="province">
              <Input placeholder="广东省" />
            </FormItem>
            <FormItem label="城市" field="city">
              <Input placeholder="深圳市" />
            </FormItem>
            <FormItem label="区县" field="district">
              <Input placeholder="南山区" />
            </FormItem>
          </div>
        </Form>
      </Modal>
    </>
  );
}
