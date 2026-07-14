"use client";

import { useEffect, useMemo, useState } from "react";
import { Button, Card, Input, Message, Modal, Skeleton, Statistic, Upload } from "@arco-design/web-react";
import {
  IconCheckCircle,
  IconDownload,
  IconExclamationCircle,
  IconSafe,
  IconStorage,
  IconUpload,
} from "@arco-design/web-react/icon";
import { backupApi, type BackupStatus, type BackupValidation } from "@/lib/api/backup";
import PageHeader from "@/components/common/PageHeader";

const datasetLabels: Record<string, string> = {
  users: "用户账号",
  registration_invites: "注册邀请",
  companies: "公司与家庭主体",
  departments: "部门",
  employees: "员工档案",
  employee_certificates: "员工证书",
  employee_experiences: "员工履历",
  employment_events: "人事事件",
  accounts: "资金账户",
  categories: "经营分类",
  budgets: "预算",
  transactions: "经营流水",
  ledgers: "账本",
  ledger_members: "账本成员",
  recurring_items: "周期事项",
  tax_items: "税务事项",
  entity_transfers: "主体往来",
  receipt_vouchers: "票据凭证",
  payroll_runs: "薪酬批次",
  payroll_run_items: "薪酬明细",
  audit_logs: "审计日志",
  notifications: "通知",
  notification_preferences: "通知偏好",
};

type SelectedBackup = {
  file: File;
  validation: BackupValidation;
};

export default function BackupPage() {
  const [status, setStatus] = useState<BackupStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const [validating, setValidating] = useState(false);
  const [selectedBackup, setSelectedBackup] = useState<SelectedBackup | null>(null);
  const [restoreVisible, setRestoreVisible] = useState(false);
  const [restoreConfirmation, setRestoreConfirmation] = useState("");
  const [restoring, setRestoring] = useState(false);

  const loadStatus = async () => {
    setLoading(true);
    setStatusError(null);
    try {
      const response = await backupApi.status();
      setStatus(response.data);
    } catch {
      setStatusError("备份范围统计加载失败，请确认后端服务状态。");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let cancelled = false;
    const loadInitialStatus = async () => {
      try {
        const response = await backupApi.status();
        if (!cancelled) setStatus(response.data);
      } catch {
        if (!cancelled) setStatusError("备份范围统计加载失败，请确认后端服务状态。");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void loadInitialStatus();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleExport = async () => {
    setExporting(true);
    try {
      const response = await backupApi.export();
      const blob = new Blob([response.data], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `mamoji-structured-backup-${new Date().toISOString().slice(0, 10)}.json`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      Message.success("全量结构化快照已导出");
    } catch {
      Message.error("结构化快照导出失败");
    } finally {
      setExporting(false);
    }
  };

  const validateFile = async (file: File) => {
    setValidating(true);
    setSelectedBackup(null);
    try {
      const response = await backupApi.validate(file);
      setSelectedBackup({ file, validation: response.data });
      if (response.data.valid) Message.success("备份完整性校验通过");
      else Message.warning(response.data.message || "备份未通过校验");
    } catch {
      setSelectedBackup({
        file,
        validation: { valid: false, restorable: false, message: "校验请求失败，请稍后重试。" },
      });
      Message.error("备份文件校验失败");
    } finally {
      setValidating(false);
    }
  };

  const restoreBackup = async () => {
    if (!selectedBackup || restoreConfirmation !== "RESTORE") return;
    setRestoring(true);
    try {
      const response = await backupApi.restore(selectedBackup.file, {
        confirmation: restoreConfirmation,
        dryRun: false,
      });
      if (!response.data.restored) throw new Error("restore failed");
      Message.success("结构化业务数据恢复完成");
      setRestoreVisible(false);
      setRestoreConfirmation("");
      setSelectedBackup(null);
      await loadStatus();
    } catch {
      Message.error("恢复失败，数据库事务已回滚");
    } finally {
      setRestoring(false);
    }
  };

  const previewCounts = useMemo(
    () => Object.entries(selectedBackup?.validation.counts || {})
      .filter(([, count]) => count > 0)
      .sort(([left], [right]) => (datasetLabels[left] || left).localeCompare(datasetLabels[right] || right, "zh-CN")),
    [selectedBackup]
  );

  return (
    <div className="mx-auto w-full max-w-[1680px] animate-fade-in">
      <PageHeader
        title="备份、校验与恢复"
        subtitle="导出全量结构化业务数据，执行完整性预检，并通过二次确认进行事务化恢复"
        icon={<IconStorage />}
        extra={<Button type="primary" icon={<IconDownload />} loading={exporting} onClick={handleExport}>导出结构化快照</Button>}
      />

      <div className="mb-5 grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1.35fr)_minmax(360px,0.65fr)]">
        <div className="rounded-2xl border p-4 sm:p-5" style={{ borderColor: "rgba(22, 93, 255, 0.24)", background: "linear-gradient(135deg, rgba(22, 93, 255, 0.09), rgba(99, 102, 241, 0.05))" }}>
          <div className="flex items-start gap-3">
            <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl" style={{ backgroundColor: "rgba(22, 93, 255, 0.12)", color: "var(--color-primary)" }}><IconSafe /></span>
            <div>
              <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>两级数据保护已经区分清楚</div>
              <div className="mt-1 text-sm leading-6" style={{ color: "var(--text-color-2)" }}>
                本页结构化快照覆盖账号、经营、财务、HR、薪酬、税务、票据元数据、通知和审计记录；生产运维备份继续负责 PostgreSQL 与 MinIO 附件字节的一致性恢复。
              </div>
            </div>
          </div>
        </div>
        <div className="rounded-3xl border p-4 sm:p-5" style={{ borderColor: "var(--color-warning-border)", backgroundColor: "var(--color-warning-soft)" }}>
          <div className="flex items-start gap-3">
            <IconExclamationCircle className="mt-1 shrink-0" style={{ color: "var(--color-warning)" }} />
            <div className="text-sm leading-6" style={{ color: "var(--text-color-2)" }}>
              结构化文件含敏感信息且不包含 MinIO 文件内容。生产灾备必须同时保留运维脚本生成的数据库与对象存储归档。
            </div>
          </div>
        </div>
      </div>

      <Card className="mb-5" style={{ borderRadius: 14 }} title="当前结构化快照范围">
        {loading ? (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">{Array.from({ length: 12 }, (_, index) => <Skeleton key={index} />)}</div>
        ) : statusError ? (
          <div className="flex items-center justify-between gap-3 rounded-xl border p-4" style={{ borderColor: "rgba(239, 68, 68, 0.28)", color: "rgb(var(--red-6))" }}>
            <span>{statusError}</span><Button size="small" onClick={() => void loadStatus()}>重试</Button>
          </div>
        ) : status ? (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
            <StatusMetric title="用户" value={status.users} />
            <StatusMetric title="账户" value={status.accounts} />
            <StatusMetric title="流水" value={status.transactions} />
            <StatusMetric title="预算" value={status.budgets} />
            <StatusMetric title="员工" value={status.employees} />
            <StatusMetric title="税务" value={status.taxItems} />
            <StatusMetric title="票据" value={status.receipts} />
            <StatusMetric title="薪酬批次" value={status.payrollRuns} />
            <StatusMetric title="通知" value={status.notifications} />
            <StatusMetric title="账本" value={status.ledgers} />
            <StatusMetric title="分类" value={status.categories} />
            <StatusMetric title="数据集" value={status.datasets} />
          </div>
        ) : null}
      </Card>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Card style={{ borderRadius: 14, height: "100%" }} title="导出全量结构化快照">
          <div className="flex h-full flex-col">
            <p className="mt-0 text-sm leading-6" style={{ color: "var(--text-color-3)" }}>
              服务端按固定数据集顺序导出并生成 SHA-256 校验和。会话令牌、异步事件锁和通知投递尝试不会进入快照，避免恢复后重放不安全状态。
            </p>
            <div className="bi-check-grid grid grid-cols-2 sm:grid-cols-3">
              {Object.values(datasetLabels).map((label) => (
                <div key={label} className="flex items-center gap-2 rounded-lg px-2.5 py-2 text-xs" style={{ backgroundColor: "var(--bg-color-page)", color: "var(--text-color-2)" }}>
                  <IconCheckCircle className="shrink-0" style={{ color: "var(--color-success)" }} />{label}
                </div>
              ))}
            </div>
            <Button className="mt-5" type="primary" icon={<IconDownload />} loading={exporting} onClick={handleExport} long>
              {exporting ? "正在生成带校验和的快照" : "下载结构化快照"}
            </Button>
          </div>
        </Card>

        <Card style={{ borderRadius: 14, height: "100%" }} title="恢复预检与受控恢复">
          <div className="flex h-full flex-col">
            <p className="mt-0 text-sm leading-6" style={{ color: "var(--text-color-3)" }}>
              先验证格式版本、必需数据集和校验和；只有预检通过后才开放恢复按钮。正式恢复在单个数据库事务中完成，失败自动回滚。
            </p>
            <Upload
              accept=".json,application/json"
              disabled={validating || restoring}
              showUploadList={false}
              beforeUpload={async (file) => {
                await validateFile(file);
                return false;
              }}
            >
              <Button type="outline" icon={<IconUpload />} loading={validating} long>{validating ? "正在校验完整性" : "选择结构化备份并预检"}</Button>
            </Upload>

            {selectedBackup ? (
              <div className="mt-4 rounded-xl border p-4" style={{ borderColor: selectedBackup.validation.valid ? "rgba(16, 185, 129, 0.3)" : "rgba(239, 68, 68, 0.28)", backgroundColor: selectedBackup.validation.valid ? "rgba(16, 185, 129, 0.06)" : "rgba(239, 68, 68, 0.05)" }}>
                <div className="flex items-start gap-2">
                  {selectedBackup.validation.valid ? <IconCheckCircle className="mt-0.5" style={{ color: "var(--color-success)" }} /> : <IconExclamationCircle className="mt-0.5" style={{ color: "var(--color-danger)" }} />}
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedBackup.file.name}</div>
                    <div className="mt-1 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{selectedBackup.validation.message}</div>
                    {selectedBackup.validation.checksum ? <div className="mt-2 truncate font-mono text-[11px]" title={selectedBackup.validation.checksum} style={{ color: "var(--text-color-4)" }}>SHA-256 {selectedBackup.validation.checksum}</div> : null}
                  </div>
                </div>
                {previewCounts.length > 0 ? (
                  <div className="mt-3 grid max-h-36 grid-cols-2 gap-1.5 overflow-y-auto sm:grid-cols-3">
                    {previewCounts.map(([key, count]) => <div key={key} className="rounded-md px-2 py-1 text-xs" style={{ backgroundColor: "var(--bg-color-card)", color: "var(--text-color-2)" }}>{datasetLabels[key] || key} · {count}</div>)}
                  </div>
                ) : null}
                {selectedBackup.validation.restorable ? <Button className="mt-4" status="danger" icon={<IconStorage />} onClick={() => setRestoreVisible(true)} long>进入恢复确认</Button> : null}
              </div>
            ) : null}
          </div>
        </Card>
      </div>

      <Modal
        title="确认恢复结构化业务数据"
        visible={restoreVisible}
        okText="执行事务化恢复"
        cancelText="取消"
        okButtonProps={{ status: "danger", disabled: restoreConfirmation !== "RESTORE", loading: restoring }}
        maskClosable={!restoring}
        closable={!restoring}
        onCancel={() => { if (!restoring) { setRestoreVisible(false); setRestoreConfirmation(""); } }}
        onOk={() => void restoreBackup()}
      >
        <div className="rounded-xl border p-3 text-sm leading-6" style={{ borderColor: "rgba(239, 68, 68, 0.28)", backgroundColor: "rgba(239, 68, 68, 0.06)", color: "var(--text-color-2)" }}>
          该操作会替换当前结构化业务数据。数据库异常会自动回滚，但 MinIO 附件不会由本次操作写入或删除。请先确认已经保存当前快照和生产级完整备份。
        </div>
        <div className="mt-4 text-sm font-medium" style={{ color: "var(--text-color-1)" }}>输入 RESTORE 继续</div>
        <Input className="mt-2" value={restoreConfirmation} onChange={setRestoreConfirmation} placeholder="RESTORE" disabled={restoring} />
      </Modal>
    </div>
  );
}

function StatusMetric({ title, value }: { title: string; value: number }) {
  return <div className="rounded-xl border px-3 py-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}><Statistic title={title} value={value} /></div>;
}
