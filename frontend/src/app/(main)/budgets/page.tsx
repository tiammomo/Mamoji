"use client";
import { useEffect, useState } from "react";
import { Card, Button, Modal, Form, Input, DatePicker, Slider, Select, Message, Tag } from "@arco-design/web-react";
import { IconPlus, IconDelete, IconEdit } from "@arco-design/web-react/icon";
import { useTranslations } from "next-intl";
import { budgetApi } from "@/lib/api/budgets";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import PageHeader from "@/components/common/PageHeader";
import BudgetProgress from "@/components/common/BudgetProgress";
import RiskBadge from "@/components/common/RiskBadge";
import EmptyState from "@/components/common/EmptyState";
import AppPagination from "@/components/common/AppPagination";
import { formatAmount, formatDate } from "@/lib/utils/format";
import type { Budget, BudgetQuery, CreateBudgetDTO, BudgetStatus } from "@/lib/types";

const FormItem = Form.Item;

export default function BudgetsPage() {
  const t = useTranslations("budget");
  const { categories, fetchCategories } = useCategoryStore();
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(12);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [keyword, setKeyword] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [status, setStatus] = useState<string>("all");
  const [form] = Form.useForm();

  const fetchData = async (params?: BudgetQuery) => {
    try {
      const res = await budgetApi.list(params);
      setBudgets(res.data.content);
      setTotal(res.data.totalElements);
    } catch {
      // silent
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let cancelled = false;

    const loadBudgets = async () => {
      try {
        const res = await budgetApi.list({ page: 0, size: 12 });
        if (!cancelled) {
          setBudgets(res.data.content);
          setTotal(res.data.totalElements);
        }
      } catch {
        // silent
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchCategories();
    void loadBudgets();

    return () => {
      cancelled = true;
    };
  }, [fetchCategories]);

  const currentQuery = (nextPage = pageIndex, nextPageSize = pageSize): BudgetQuery => ({
    keyword: keyword || undefined,
    startDate: startDate || undefined,
    endDate: endDate || undefined,
    status: status === "all" ? undefined : Number(status) as BudgetStatus,
    page: nextPage,
    size: nextPageSize,
  });

  const handleSearch = () => {
    setPageIndex(0);
    setLoading(true);
    fetchData(currentQuery(0, pageSize));
  };

  const handleReset = () => {
    setKeyword("");
    setStartDate("");
    setEndDate("");
    setStatus("all");
    setPageIndex(0);
    setLoading(true);
    fetchData({ page: 0, size: pageSize });
  };

  const handlePageChange = (page: number, size: number) => {
    const nextPageIndex = page - 1;
    setPageIndex(nextPageIndex);
    setPageSize(size);
    setLoading(true);
    fetchData(currentQuery(nextPageIndex, size));
  };

  const handleSubmit = async (values: CreateBudgetDTO & { categoryId?: number }) => {
    try {
      const data = {
        ...values,
        categoryId: values.categoryId || undefined,
      };
      if (editingId) {
        await budgetApi.update(editingId, data);
        Message.success("更新成功");
      } else {
        await budgetApi.create(data);
        Message.success("创建成功");
      }
      setModalVisible(false);
      form.resetFields();
      setEditingId(null);
      fetchData(currentQuery());
    } catch {
      Message.error("操作失败");
    }
  };

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: "确认删除",
      content: "确定要删除这个预算吗？",
      onOk: async () => {
        try {
          await budgetApi.delete(id);
          Message.success("删除成功");
          fetchData(currentQuery());
        } catch {
          Message.error("删除失败");
        }
      },
    });
  };

  const openEdit = (budget: Budget) => {
    setEditingId(budget.id);
    form.setFieldsValue({
      ...budget,
      categoryId: budget.categoryId || undefined,
    });
    setModalVisible(true);
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title={t("title")}
        icon="🎯"
        extra={
          <Button
            type="primary"
            icon={<IconPlus />}
            onClick={() => {
              setEditingId(null);
              form.resetFields();
              setModalVisible(true);
            }}
          >
            {t("new")}
          </Button>
        }
      />

      <Card className="mb-6" style={{ borderRadius: 16 }}>
        <div className="grid grid-cols-1 md:grid-cols-[1.2fr_1fr_1fr_1fr_auto_auto] gap-3 items-center">
          <Input
            placeholder="搜索预算名称"
            value={keyword}
            onChange={setKeyword}
            onPressEnter={handleSearch}
            style={{ borderRadius: 12 }}
          />
          <DatePicker
            value={startDate}
            onChange={(value) => setStartDate(String(value || ""))}
            placeholder="起始日期"
            className="w-full"
            style={{ borderRadius: 12 }}
          />
          <DatePicker
            value={endDate}
            onChange={(value) => setEndDate(String(value || ""))}
            placeholder="结束日期"
            className="w-full"
            style={{ borderRadius: 12 }}
          />
          <Select value={status} onChange={setStatus} style={{ borderRadius: 12 }}>
            <Select.Option value="all">全部状态</Select.Option>
            <Select.Option value="1">进行中</Select.Option>
            <Select.Option value="2">已完成</Select.Option>
            <Select.Option value="3">已超支</Select.Option>
            <Select.Option value="0">已停用</Select.Option>
          </Select>
          <Button type="primary" onClick={handleSearch}>筛选</Button>
          <Button onClick={handleReset}>重置</Button>
        </div>
      </Card>

      {budgets.length === 0 && !loading ? (
        <Card style={{ borderRadius: 16 }}>
          <EmptyState
            icon="🎯"
            title="暂无预算"
            description="创建预算来控制公司、部门或项目成本"
            actionText="创建预算"
            onAction={() => setModalVisible(true)}
          />
        </Card>
      ) : (
        <>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {budgets.map((budget, index) => (
              <div
              key={budget.id}
              className="stat-card animate-fade-in hover-lift"
              style={{ animationDelay: `${index * 100}ms` }}
            >
              <div className="flex justify-between items-start mb-4">
                <div>
                  <div className="font-medium text-base mb-2">{budget.name}</div>
                  <div className="flex gap-2">
                    {budget.categoryName ? (
                      <Tag color="blue" style={{ borderRadius: 6 }}>
                        {budget.categoryName}
                      </Tag>
                    ) : (
                      <Tag color="purple" style={{ borderRadius: 6 }}>
                        {t("totalBudget")}
                      </Tag>
                    )}
                    <RiskBadge level={budget.riskLevel} />
                  </div>
                </div>
                <div className="flex gap-1">
                  <Button
                    type="text"
                    size="mini"
                    icon={<IconEdit />}
                    onClick={() => openEdit(budget)}
                    style={{ color: "var(--text-color-3)" }}
                  />
                  <Button
                    type="text"
                    size="mini"
                    status="danger"
                    icon={<IconDelete />}
                    onClick={() => handleDelete(budget.id)}
                  />
                </div>
              </div>

              <BudgetProgress
                spent={budget.spent}
                amount={budget.amount}
                usageRate={budget.usageRate}
                warningThreshold={budget.warningThreshold}
                riskLevel={budget.riskLevel}
              />

              <div className="flex justify-between mt-4 pt-3 border-t" style={{ borderColor: "var(--border-color-light)" }}>
                <div>
                  <div className="text-xs" style={{ color: "var(--text-color-4)" }}>剩余</div>
                  <div className="font-medium">{formatAmount(budget.remainingAmount)}</div>
                </div>
                <div className="text-right">
                  <div className="text-xs" style={{ color: "var(--text-color-4)" }}>周期</div>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>
                    {formatDate(budget.startDate)} - {formatDate(budget.endDate)}
                  </div>
                </div>
              </div>
              </div>
            ))}
          </div>
          <AppPagination
            current={pageIndex + 1}
            pageSize={pageSize}
            total={total}
            pageSizeOptions={[6, 12, 24, 48]}
            onChange={handlePageChange}
          />
        </>
      )}

      <Modal
        title={editingId ? "编辑预算" : t("new")}
        visible={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={() => form.submit()}
        style={{ borderRadius: 16 }}
      >
        <Form form={form} layout="vertical" onSubmit={handleSubmit}>
          <FormItem label={t("name")} field="name" rules={[{ required: true, message: "请输入名称" }]}>
            <Input placeholder="预算名称" style={{ borderRadius: 12 }} />
          </FormItem>
          <FormItem label={t("amount")} field="amount" rules={[{ required: true, message: "请输入金额" }]}>
            <Input type="number" placeholder="0.00" style={{ borderRadius: 12 }} />
          </FormItem>
          <FormItem label={t("category")} field="categoryId">
            <Select placeholder="选择分类（可选，留空为总预算）" allowClear style={{ borderRadius: 12 }}>
              {categories.filter((c) => c.type === "expense").map((cat) => (
                <Select.Option key={cat.id} value={cat.id}>
                  {cat.icon} {cat.name}
                </Select.Option>
              ))}
            </Select>
          </FormItem>
          <FormItem label={t("startDate")} field="startDate" rules={[{ required: true, message: "请选择开始日期" }]}>
            <DatePicker className="w-full" style={{ borderRadius: 12 }} />
          </FormItem>
          <FormItem label={t("endDate")} field="endDate" rules={[{ required: true, message: "请选择结束日期" }]}>
            <DatePicker className="w-full" style={{ borderRadius: 12 }} />
          </FormItem>
          <FormItem label={`${t("warningThreshold")}%`} field="warningThreshold" initialValue={80}>
            <Slider min={0} max={100} marks={{ 0: "0%", 50: "50%", 80: "80%", 100: "100%" }} />
          </FormItem>
        </Form>
      </Modal>
    </div>
  );
}
