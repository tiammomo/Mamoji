"use client";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Dropdown, Form, Input, Message, Modal, Select, Spin } from "@arco-design/web-react";
import { IconBranch, IconCheck, IconDown, IconLocation, IconPlus } from "@arco-design/web-react/icon";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useAppStore } from "@/lib/stores/appStore";
import { useAuthStore } from "@/lib/stores/authStore";
import type { Company } from "@/lib/types";
import { useTranslations } from "next-intl";

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

const regionText = (company: Company, fallback: string) => (
  company.operatingRegion
  || [company.country, company.province, company.city, company.district].filter(Boolean).join("/")
  || fallback
);

const policyKeyFor = (values: CompanyFormValues) => {
  if (values.entityType === "household") return "CN-HOUSEHOLD-ASSET-PROFILE";
  const city = values.city || "";
  if (city.includes("深圳")) return "CN-GD-SZ-DEMO-POLICY";
  return "CN-DEFAULT-DEMO-POLICY";
};

export default function CompanySwitcher() {
  const t = useTranslations("companySwitcher");
  const [companies, setCompanies] = useState<Company[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<CompanyFormValues>();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const setActiveCompanyId = useAppStore((state) => state.setActiveCompanyId);
  const setActiveSubjectType = useAppStore((state) => state.setActiveSubjectType);
  const user = useAuthStore((state) => state.user);
  const accessContext = useAuthStore((state) => state.accessContext);
  const refreshAccessContext = useAuthStore((state) => state.refreshAccessContext);
  const householdEnabled = accessContext?.modules.enabled.includes("household") ?? false;
  const canCreateCompany = user?.role === 1 || accessContext?.permissions.includes("company.create") === true;
  const selectedEntityType = Form.useWatch("entityType", form) || "company";

  const activeCompany = useMemo(
    () => companies.find((company) => company.id === activeCompanyId) || companies[0] || null,
    [activeCompanyId, companies]
  );

  const activateCompany = useCallback((company: Company) => {
    setActiveCompanyId(company.id);
    setActiveSubjectType(company.entityType === "household" ? "household" : "company");
    void refreshAccessContext(company.id).catch(() => {
      Message.error(t("loadFailed"));
    });
  }, [refreshAccessContext, setActiveCompanyId, setActiveSubjectType, t]);

  const loadCompanies = useCallback(async () => {
    try {
      setLoading(true);
      const res = await enterpriseApi.companies();
      setCompanies(res.data);

      const storedCompanyId = useAppStore.getState().activeCompanyId;
      if (res.data.length > 0 && (!storedCompanyId || !res.data.some((company) => company.id === storedCompanyId))) {
        activateCompany(res.data[0]);
      } else {
        const currentCompany = res.data.find((company) => company.id === storedCompanyId);
        if (currentCompany) {
          setActiveSubjectType(currentCompany.entityType === "household" ? "household" : "company");
        }
      }
    } catch {
      Message.error(t("loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [activateCompany, setActiveSubjectType, t]);

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
      activateCompany(res.data);
      setModalVisible(false);
      form.resetFields();
      Message.success(t("createSuccess"));
    } catch {
      Message.error(t("createFailed"));
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
        <div className="text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{t("title")}</div>
        <div className="text-xs mt-1" style={{ color: "var(--text-color-3)" }}>{t("description")}</div>
      </div>

      <div className="max-h-[264px] overflow-y-auto p-2">
        {loading ? (
          <div className="flex h-20 items-center justify-center">
            <Spin />
          </div>
        ) : companies.length === 0 ? (
          <div className="flex h-20 items-center justify-center px-3 text-center text-sm" style={{ color: "var(--text-color-3)" }}>
            {t("empty")}
          </div>
        ) : companies.map((company) => {
          const isActive = company.id === activeCompany?.id;
          const subjectLabel = company.entityType === "household" ? t("householdSubject") : t("companySubject");
          return (
            <button
              key={company.id}
              type="button"
              className="flex h-16 w-full cursor-pointer items-center gap-3 rounded-lg border-0 bg-transparent px-3 text-left transition-colors hover:bg-black/5 dark:hover:bg-white/5"
              onClick={() => {
                activateCompany(company);
              }}
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
                  {subjectLabel} · {regionText(company, t("regionFallback"))} · {company.taxpayerType}
                </span>
              </span>
              {isActive && <IconCheck style={{ color: "var(--color-primary)" }} />}
            </button>
          );
        })}
      </div>

      {canCreateCompany && <div className="flex items-center border-t px-2" style={{ height: 52, borderColor: "var(--border-color)" }}>
        <button
          type="button"
          className="flex h-9 w-full cursor-pointer items-center justify-center gap-2 rounded-lg border-0 bg-transparent px-3 text-sm font-medium transition-colors hover:bg-black/5 dark:hover:bg-white/5"
          style={{ color: "var(--color-primary)" }}
          onClick={openCreateModal}
        >
          <IconPlus />
          {t("addSubject")}
        </button>
      </div>}
    </div>
  );

  return (
    <>
      <Dropdown droplist={droplist} trigger="click" position="br">
        <button
          data-company-switcher-trigger
          type="button"
          aria-label={`切换主体，当前：${activeCompany?.name || "未选择主体"}`}
          title={householdEnabled ? "切换公司或家庭主体" : "切换公司主体"}
          className="flex h-12 w-12 cursor-pointer items-center gap-2 rounded-full border px-2 text-left shadow-sm transition-all hover:-translate-y-px hover:shadow-md md:w-auto md:min-w-[252px] md:max-w-[360px] md:pl-2 md:pr-2"
          style={{
            borderColor: "rgba(99, 102, 241, 0.16)",
            color: "var(--text-color-2)",
            backgroundColor: "var(--bg-color-card)",
          }}
        >
          <span
            className="grid h-9 w-9 shrink-0 place-items-center rounded-full"
            style={{
              background: "linear-gradient(135deg, rgba(99,102,241,0.16) 0%, rgba(37,99,235,0.1) 100%)",
              color: "var(--color-primary)",
            }}
          >
            <IconBranch />
          </span>
          <span className="hidden min-w-0 flex-1 md:block">
            <span className="block max-w-[214px] truncate text-sm font-semibold leading-5 xl:max-w-[276px]" style={{ color: "var(--text-color-1)" }}>
              {loading ? t("loading") : activeCompany?.name || t("selectSubject")}
            </span>
            {activeCompany && (
              <span className="mt-0.5 hidden max-w-[214px] items-center gap-1 truncate text-xs leading-4 xl:flex xl:max-w-[276px]" style={{ color: "var(--text-color-3)" }}>
                <span className="rounded-full px-1.5 py-0.5 text-[11px] leading-none" style={{ backgroundColor: "rgba(99, 102, 241, 0.09)", color: "var(--color-primary)" }}>
                  {activeCompany.entityType === "household" ? t("householdSubject") : t("companySubject")}
                </span>
                <span className="truncate">{regionText(activeCompany, t("regionFallback"))}</span>
              </span>
            )}
          </span>
          <span
            className="hidden h-8 w-8 shrink-0 place-items-center rounded-full md:grid"
            style={{ backgroundColor: "rgba(100, 116, 139, 0.08)", color: "var(--text-color-3)" }}
          >
            <IconDown />
          </span>
        </button>
      </Dropdown>

      <Modal
        title={t("addSubject")}
        visible={modalVisible}
        confirmLoading={creating}
        onCancel={() => setModalVisible(false)}
        onOk={() => form.submit()}
        style={{ width: 640 }}
      >
        <Form
          form={form}
          layout="vertical"
          onSubmit={handleCreateCompany}
          onValuesChange={(changed) => {
            if (householdEnabled && changed.entityType === "household") {
              form.setFieldsValue({ industry: "家庭资产管理", taxpayerType: "非经营主体" });
            }
            if (changed.entityType === "company") {
              form.setFieldsValue({ industry: "软件与信息技术服务", taxpayerType: "小规模纳税人" });
            }
          }}
        >
          <FormItem label="主体类型" field="entityType" rules={[{ required: true, message: "请选择主体类型" }]}>
            <Select>
              <Select.Option value="company">公司主体</Select.Option>
              {householdEnabled && <Select.Option value="household">家庭主体</Select.Option>}
            </Select>
          </FormItem>
          <FormItem label="主体名称" field="name" rules={[{ required: true, message: "请输入主体名称" }]}>
              <Input placeholder={householdEnabled ? "例如：广州某某贸易有限公司 / 家庭资产主体" : "例如：广州某某贸易有限公司"} />
          </FormItem>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <FormItem label="行业" field="industry" rules={[{ required: true, message: "请输入行业" }]}>
              <Input placeholder="软件与信息技术服务" />
            </FormItem>
            <FormItem label="纳税人类型" field="taxpayerType" rules={[{ required: true, message: "请选择纳税人类型" }]}>
              <Select>
                {selectedEntityType === "household" ? (
                  <Select.Option value="非经营主体">非经营主体</Select.Option>
                ) : (
                  <>
                    <Select.Option value="小规模纳税人">小规模纳税人</Select.Option>
                    <Select.Option value="一般纳税人">一般纳税人</Select.Option>
                  </>
                )}
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
