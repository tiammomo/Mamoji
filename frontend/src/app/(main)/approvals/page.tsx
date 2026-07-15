"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Card, Drawer, Empty, Form, Input, InputNumber, Message, Modal, Select, Skeleton, Tag } from "@arco-design/web-react";
import { IconCheckCircle, IconClockCircle, IconCloseCircle, IconEye, IconPlus, IconRefresh, IconSend } from "@arco-design/web-react/icon";
import PageHeader from "@/components/common/PageHeader";
import AppPagination from "@/components/common/AppPagination";
import { approvalApi } from "@/lib/api/approvals";
import { useAppStore } from "@/lib/stores/appStore";
import { useAuthStore } from "@/lib/stores/authStore";
import { formatAmount, formatDate, formatDateTime } from "@/lib/utils/format";
import type { ApprovalDetail, ApprovalPayload, ApprovalRequest, ApprovalSummary } from "@/lib/types";

const requestTypeMeta: Record<string, { label: string; color: string }> = {
  reimbursement: { label: "报销", color: "orange" },
  payment: { label: "付款", color: "arcoblue" },
  budget_adjustment: { label: "预算调整", color: "purple" },
  onboarding: { label: "入职", color: "green" },
  offboarding: { label: "离职", color: "red" },
  payroll_close: { label: "薪酬月结", color: "cyan" },
  other: { label: "其他", color: "gray" },
};

const statusMeta: Record<string, { label: string; color: string }> = {
  pending: { label: "待审批", color: "orange" },
  approved: { label: "已通过", color: "green" },
  rejected: { label: "已驳回", color: "red" },
  withdrawn: { label: "已撤回", color: "gray" },
};

const actionLabels: Record<string, string> = {
  submit: "提交申请",
  approve: "审批通过",
  reject: "驳回申请",
  withdraw: "撤回申请",
};

type CreateValues = ApprovalPayload;

export default function ApprovalsPage() {
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const user = useAuthStore((state) => state.user);
  const [rows, setRows] = useState<ApprovalRequest[]>([]);
  const [summary, setSummary] = useState<ApprovalSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [requestType, setRequestType] = useState("");
  const [createVisible, setCreateVisible] = useState(false);
  const [creating, setCreating] = useState(false);
  const [detail, setDetail] = useState<ApprovalDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [actionComment, setActionComment] = useState("");
  const [acting, setActing] = useState(false);
  const [form] = Form.useForm<CreateValues>();

  const loadData = useCallback(async (quiet = false) => {
    if (quiet) setRefreshing(true);
    else setLoading(true);
    try {
      const [listResponse, summaryResponse] = await Promise.all([
        approvalApi.list({ page: page - 1, size: pageSize, keyword: keyword || undefined, status: status || undefined, requestType: requestType || undefined }),
        approvalApi.summary(),
      ]);
      setRows(listResponse.data.content);
      setTotal(listResponse.data.totalElements);
      setSummary(summaryResponse.data);
    } catch {
      Message.error("审批数据加载失败");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [keyword, page, pageSize, requestType, status]);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      try {
        const [listResponse, summaryResponse] = await Promise.all([
          approvalApi.list({ page: page - 1, size: pageSize, keyword: keyword || undefined, status: status || undefined, requestType: requestType || undefined }),
          approvalApi.summary(),
        ]);
        if (cancelled) return;
        setRows(listResponse.data.content);
        setTotal(listResponse.data.totalElements);
        setSummary(summaryResponse.data);
      } catch {
        if (!cancelled) Message.error("审批数据加载失败");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [activeCompanyId, keyword, page, pageSize, requestType, status]);

  const summaryCards = useMemo(() => [
    { label: "待审批", value: summary?.pending || 0, hint: `${summary?.minePending || 0} 项待我处理`, icon: <IconClockCircle />, tone: "var(--color-warning)" },
    { label: "已通过", value: summary?.approved || 0, hint: "保留完整审批动作", icon: <IconCheckCircle />, tone: "var(--color-success)" },
    { label: "已驳回", value: summary?.rejected || 0, hint: "驳回必须填写意见", icon: <IconCloseCircle />, tone: "var(--color-danger)" },
    { label: "全部申请", value: summary?.total || 0, hint: "当前主体可见范围", icon: <IconSend />, tone: "var(--color-primary)" },
  ], [summary]);

  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({ requestType: "payment", entityType: "other", amount: 0 });
    setCreateVisible(true);
  };

  const submitCreate = async (values: CreateValues) => {
    setCreating(true);
    try {
      await approvalApi.create({ ...values, amount: Number(values.amount || 0) });
      Message.success("审批申请已提交");
      setCreateVisible(false);
      form.resetFields();
      setPage(1);
      await loadData(true);
    } catch {
      Message.error("审批申请提交失败");
    } finally {
      setCreating(false);
    }
  };

  const openDetail = async (id: number) => {
    setDetailLoading(true);
    setActionComment("");
    try {
      const response = await approvalApi.get(id);
      setDetail(response.data);
    } catch {
      Message.error("审批详情加载失败");
    } finally {
      setDetailLoading(false);
    }
  };

  const performAction = async (action: "approve" | "reject" | "withdraw") => {
    if (!detail) return;
    if (action === "reject" && !actionComment.trim()) {
      Message.warning("驳回时必须填写审批意见");
      return;
    }
    setActing(true);
    try {
      const response = action === "approve"
        ? await approvalApi.approve(detail.request.id, actionComment.trim() || undefined)
        : action === "reject"
          ? await approvalApi.reject(detail.request.id, actionComment.trim())
          : await approvalApi.withdraw(detail.request.id, actionComment.trim() || undefined);
      setDetail(response.data);
      setActionComment("");
      Message.success(action === "approve" ? "审批已通过" : action === "reject" ? "申请已驳回" : "申请已撤回");
      await loadData(true);
    } catch {
      Message.error("审批操作失败");
    } finally {
      setActing(false);
    }
  };

  const canDecide = Boolean(detail && detail.request.status === "pending" && (user?.role === 1 || detail.request.assigneeUserId === user?.id));
  const canWithdraw = Boolean(detail && detail.request.status === "pending" && detail.request.applicantUserId === user?.id);

  return (
    <div className="mx-auto w-full max-w-[1800px] animate-fade-in">
      <PageHeader
        title="审批与协同中心"
        subtitle="统一处理报销、付款、预算调整、入离职和薪酬月结，保留申请、意见、决定和撤回链路"
        icon={<IconCheckCircle />}
        extra={<div className="flex items-center gap-2">{refreshing ? <Tag color="arcoblue">刷新中</Tag> : null}<Button icon={<IconRefresh />} onClick={() => void loadData(true)}>刷新</Button><Button type="primary" icon={<IconPlus />} onClick={openCreate}>发起申请</Button></div>}
      />

      <div className="metric-grid metric-wrap-until-xl grid grid-cols-2 xl:grid-cols-4">
        {summaryCards.map((card) => (
          <Card className="metric-card" key={card.label} loading={loading} style={{ borderRadius: 14 }}>
            <div className="flex items-start justify-between gap-3">
              <div><div className="text-xs" style={{ color: "var(--text-color-3)" }}>{card.label}</div><div className="mt-2 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{card.value}</div><div className="mt-2 text-xs" style={{ color: "var(--text-color-4)" }}>{card.hint}</div></div>
              <span className="grid h-10 w-10 place-items-center rounded-xl" style={{ backgroundColor: "var(--color-fill-1)", color: card.tone }}>{card.icon}</span>
            </div>
          </Card>
        ))}
      </div>

      <Card className="filter-card mb-4" style={{ borderRadius: 14 }}>
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[minmax(260px,1fr)_180px_180px_auto]">
          <Input allowClear value={keyword} onChange={(value) => { setKeyword(value); setPage(1); }} placeholder="搜索审批标题或说明" />
          <Select allowClear value={status || undefined} onChange={(value) => { setStatus(value || ""); setPage(1); }} placeholder="审批状态">
            {Object.entries(statusMeta).map(([value, meta]) => <Select.Option key={value} value={value}>{meta.label}</Select.Option>)}
          </Select>
          <Select allowClear value={requestType || undefined} onChange={(value) => { setRequestType(value || ""); setPage(1); }} placeholder="申请类型">
            {Object.entries(requestTypeMeta).map(([value, meta]) => <Select.Option key={value} value={value}>{meta.label}</Select.Option>)}
          </Select>
          <Button onClick={() => { setKeyword(""); setStatus(""); setRequestType(""); setPage(1); }}>重置</Button>
        </div>
      </Card>

      <Card className={rows.length === 0 ? "bi-compact-empty" : undefined} title={<div className="flex items-center gap-2"><span>审批台账</span><Tag color="arcoblue">{total}</Tag></div>} style={{ borderRadius: 14 }}>
        {loading ? <Skeleton /> : rows.length === 0 ? <Empty description="暂无匹配审批申请" /> : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[980px] table-fixed border-collapse text-sm">
              <colgroup>
                <col style={{ width: "24%" }} />
                <col style={{ width: "11%" }} />
                <col style={{ width: "13%" }} />
                <col style={{ width: "18%" }} />
                <col style={{ width: "11%" }} />
                <col style={{ width: "15%" }} />
                <col style={{ width: "8%" }} />
              </colgroup>
              <thead><tr style={{ backgroundColor: "var(--bg-color-page)" }}>{[
                { label: "申请", align: "text-left" },
                { label: "类型", align: "text-center" },
                { label: "金额", align: "text-right" },
                { label: "申请人 / 审批人", align: "text-center" },
                { label: "状态", align: "text-center" },
                { label: "提交时间", align: "text-center" },
                { label: "操作", align: "text-center" },
              ].map((column) => <th key={column.label} className={`px-4 py-3 font-medium ${column.align}`} style={{ color: "var(--text-color-2)" }}>{column.label}</th>)}</tr></thead>
              <tbody>{rows.map((row) => {
                const type = requestTypeMeta[row.requestType] || requestTypeMeta.other;
                const state = statusMeta[row.status] || { label: row.status, color: "gray" };
                return <tr key={row.id} className="border-b" style={{ borderColor: "var(--border-color-light)" }}>
                  <td className="px-4 py-4 align-middle"><button type="button" className="cursor-pointer text-left" onClick={() => void openDetail(row.id)}><div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{row.title}</div><div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>#{row.id}{row.entityId ? ` · ${row.entityType} #${row.entityId}` : ""}</div></button></td>
                  <td className="px-4 py-4 text-center align-middle"><Tag color={type.color}>{type.label}</Tag></td>
                  <td className="px-4 py-4 text-right font-medium whitespace-nowrap align-middle" style={{ color: "var(--text-color-1)" }}>{row.amount > 0 ? formatAmount(row.amount) : "--"}</td>
                  <td className="px-4 py-4 text-center text-xs align-middle" style={{ color: "var(--text-color-3)" }}>申请人 #{row.applicantUserId}<br />审批人 {row.assigneeUserId ? `#${row.assigneeUserId}` : "待分配"}</td>
                  <td className="px-4 py-4 text-center align-middle"><Tag color={state.color}>{state.label}</Tag></td>
                  <td className="px-4 py-4 text-center text-xs align-middle" style={{ color: "var(--text-color-3)" }}>{formatDateTime(row.createdAt)}</td>
                  <td className="px-4 py-4 text-center align-middle"><Button type="text" size="small" icon={<IconEye />} onClick={() => void openDetail(row.id)}>详情</Button></td>
                </tr>;
              })}</tbody>
            </table>
          </div>
        )}
        <AppPagination current={page} pageSize={pageSize} total={total} pageSizeOptions={[10, 20, 50, 100]} onChange={(nextPage, nextSize) => { setPage(nextPage); setPageSize(nextSize); }} />
      </Card>

      <Modal title="发起审批申请" visible={createVisible} style={{ width: 680 }} confirmLoading={creating} onCancel={() => setCreateVisible(false)} onOk={() => form.submit()}>
        <Form form={form} layout="vertical" onSubmit={submitCreate}>
          <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
            <Form.Item label="申请类型" field="requestType" rules={[{ required: true, message: "请选择申请类型" }]}><Select>{Object.entries(requestTypeMeta).map(([value, meta]) => <Select.Option key={value} value={value}>{meta.label}</Select.Option>)}</Select></Form.Item>
            <Form.Item label="申请金额" field="amount"><InputNumber min={0} precision={2} className="w-full" placeholder="非金额审批可填 0" /></Form.Item>
          </div>
          <Form.Item label="申请标题" field="title" rules={[{ required: true, message: "请输入申请标题" }]}><Input maxLength={160} showWordLimit placeholder="例如：7 月办公设备采购付款" /></Form.Item>
          <Form.Item label="申请说明" field="description"><Input.TextArea rows={4} maxLength={1000} showWordLimit placeholder="说明业务背景、付款对象、预算依据或需要审批的事项" /></Form.Item>
          <Form.Item label="提交备注" field="comment"><Input.TextArea rows={2} maxLength={500} showWordLimit placeholder="可选，将作为审批时间线第一条意见" /></Form.Item>
          <Form.Item field="entityType" hidden><Input /></Form.Item>
        </Form>
      </Modal>

      <Drawer title="审批详情与处理记录" visible={Boolean(detail) || detailLoading} width="min(560px, 100vw)" footer={null} onCancel={() => { setDetail(null); setActionComment(""); }}>
        {detailLoading && !detail ? <Skeleton /> : detail ? (
          <div className="space-y-5">
            <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
              <div className="flex items-start justify-between gap-3"><div><div className="text-lg font-bold" style={{ color: "var(--text-color-1)" }}>{detail.request.title}</div><div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>审批单 #{detail.request.id} · {formatDate(detail.request.createdAt)}</div></div><Tag color={(statusMeta[detail.request.status] || { color: "gray" }).color}>{(statusMeta[detail.request.status] || { label: detail.request.status }).label}</Tag></div>
              {detail.request.amount > 0 ? <div className="mt-4 text-2xl font-bold" style={{ color: "var(--color-primary)" }}>{formatAmount(detail.request.amount)}</div> : null}
              {detail.request.description ? <div className="mt-4 whitespace-pre-wrap text-sm leading-6" style={{ color: "var(--text-color-2)" }}>{detail.request.description}</div> : null}
              <div className="mt-4 grid grid-cols-2 gap-3 text-xs" style={{ color: "var(--text-color-3)" }}><div>申请人 #{detail.request.applicantUserId}</div><div>审批人 {detail.request.assigneeUserId ? `#${detail.request.assigneeUserId}` : "待分配"}</div><div>业务对象 {detail.request.entityType}</div><div>对象 ID {detail.request.entityId ? `#${detail.request.entityId}` : "--"}</div></div>
            </div>

            <div><div className="mb-3 text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>处理时间线</div><div className="space-y-3">{detail.actions.map((action, index) => <div key={action.id} className="flex gap-3"><div className="flex flex-col items-center"><span className="grid h-7 w-7 place-items-center rounded-full text-xs" style={{ backgroundColor: "var(--color-fill-1)", color: "var(--color-primary)" }}>{index + 1}</span>{index < detail.actions.length - 1 ? <span className="min-h-8 w-px flex-1" style={{ backgroundColor: "var(--border-color)" }} /> : null}</div><div className="min-w-0 flex-1 pb-3"><div className="flex items-center justify-between gap-3"><span className="text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{actionLabels[action.action] || action.action}</span><span className="text-[11px]" style={{ color: "var(--text-color-4)" }}>{formatDateTime(action.createdAt)}</span></div><div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>操作人 #{action.actorUserId}</div>{action.comment ? <div className="mt-2 rounded-lg p-2.5 text-xs leading-5" style={{ backgroundColor: "var(--bg-color-page)", color: "var(--text-color-2)" }}>{action.comment}</div> : null}</div></div>)}</div></div>

            {(canDecide || canWithdraw) ? <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}><div className="mb-2 text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>处理意见</div><Input.TextArea value={actionComment} onChange={setActionComment} rows={3} maxLength={500} showWordLimit placeholder="通过意见可选；驳回必须填写原因" /><div className="mt-3 flex flex-wrap gap-2">{canDecide ? <><Button type="primary" icon={<IconCheckCircle />} loading={acting} onClick={() => void performAction("approve")}>通过</Button><Button status="danger" icon={<IconCloseCircle />} loading={acting} onClick={() => void performAction("reject")}>驳回</Button></> : null}{canWithdraw ? <Button loading={acting} onClick={() => void performAction("withdraw")}>撤回申请</Button> : null}</div></div> : null}
          </div>
        ) : null}
      </Drawer>
    </div>
  );
}
