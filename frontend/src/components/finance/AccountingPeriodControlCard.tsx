"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, Card, Input, Message, Modal, Skeleton, Tag } from "@arco-design/web-react";
import { IconCheckCircle, IconExclamationCircle, IconLock, IconRefresh } from "@arco-design/web-react/icon";
import { accountingPeriodApi } from "@/lib/api/accounting-periods";
import { problemCode } from "@/lib/api/problem";
import { useAuthStore } from "@/lib/stores/authStore";
import type { AccountingPeriodControl } from "@/lib/types";

export type ClosingTask = {
  label: string;
  unavailable: boolean;
  done: boolean;
  detail: string;
};

type Props = {
  loading: boolean;
  companyId: number | null;
  tasks: ClosingTask[];
};

const monthOf = (date: Date) => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  return `${year}-${month}`;
};

const previousMonth = () => {
  const date = new Date();
  date.setDate(1);
  date.setMonth(date.getMonth() - 1);
  return monthOf(date);
};

const actionLabel: Record<AccountingPeriodControl["lastAction"], string> = {
  INITIAL: "尚未关账",
  CLOSE: "最近执行关账",
  REOPEN: "最近执行反结账",
};

export default function AccountingPeriodControlCard({ loading, companyId, tasks }: Props) {
  const user = useAuthStore((state) => state.user);
  const accessContext = useAuthStore((state) => state.accessContext);
  const [control, setControl] = useState<AccountingPeriodControl | null>(null);
  const [controlLoading, setControlLoading] = useState(true);
  const [closeVisible, setCloseVisible] = useState(false);
  const [reopenVisible, setReopenVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [targetMonth, setTargetMonth] = useState(previousMonth);
  const [reopenReason, setReopenReason] = useState("");
  const controlRequestId = useRef(0);

  const companyWideScope = ["group", "company", "company_set"].includes(accessContext?.scope ?? "");
  const canManage = user?.role === 1 || Boolean(
    companyId !== null
    && accessContext?.company.id === companyId
    && companyWideScope
    && accessContext.permissions.includes("finance.write")
  );
  const activeControl = companyId === null || control?.companyId === companyId ? control : null;
  const activeControlLoading = controlLoading || (companyId !== null && control?.companyId !== companyId);
  const tasksReady = tasks.every((task) => task.done && !task.unavailable);
  const currentClosedMonth = activeControl?.closedThrough?.slice(0, 7) ?? null;
  const targetValid = Boolean(
    targetMonth
    && targetMonth <= previousMonth()
    && (!currentClosedMonth || targetMonth > currentClosedMonth)
  );

  const loadControl = async () => {
    const requestId = ++controlRequestId.current;
    setControlLoading(true);
    try {
      const response = await accountingPeriodApi.current(companyId ?? undefined);
      if (requestId === controlRequestId.current) setControl(response.data);
    } catch {
      if (requestId === controlRequestId.current) {
        setControl(null);
        Message.error("会计期间状态加载失败");
      }
    } finally {
      if (requestId === controlRequestId.current) setControlLoading(false);
    }
  };

  useEffect(() => {
    const requestId = ++controlRequestId.current;
    void accountingPeriodApi.current(companyId ?? undefined)
      .then((response) => {
        if (requestId === controlRequestId.current) {
          setControl(response.data);
          setControlLoading(false);
        }
      })
      .catch(() => {
        if (requestId === controlRequestId.current) {
          setControl(null);
          setControlLoading(false);
          Message.error("会计期间状态加载失败");
        }
      });
    return () => {
      if (requestId === controlRequestId.current) controlRequestId.current += 1;
    };
  }, [companyId]);

  const status = useMemo(() => {
    if (!activeControl?.closedThrough) return { color: "gray", label: "账期开放" };
    return { color: "green", label: `已关闭至 ${activeControl.closedThrough}` };
  }, [activeControl]);

  const closePeriod = async () => {
    if (!activeControl || !targetValid) return;
    setSubmitting(true);
    try {
      const response = await accountingPeriodApi.close({
        companyId: companyId ?? undefined,
        version: activeControl.version,
        throughMonth: targetMonth,
      });
      setControl(response.data);
      setCloseVisible(false);
      Message.success(`会计期间已关闭至 ${response.data.closedThrough}`);
    } catch (error) {
      if (problemCode(error) === "concurrent_modification") {
        Message.warning("账期状态已被其他管理员修改，已重新加载");
        await loadControl();
      } else {
        Message.error("关账失败，请检查目标月份和当前权限");
      }
    } finally {
      setSubmitting(false);
    }
  };

  const reopenPeriod = async () => {
    if (!activeControl || reopenReason.trim().length < 5) return;
    setSubmitting(true);
    try {
      const response = await accountingPeriodApi.reopen({
        companyId: companyId ?? undefined,
        version: activeControl.version,
        reason: reopenReason.trim(),
      });
      setControl(response.data);
      setReopenReason("");
      setReopenVisible(false);
      Message.success("反结账完成，历史期间已重新开放");
    } catch (error) {
      if (problemCode(error) === "concurrent_modification") {
        Message.warning("账期状态已被其他管理员修改，已重新加载");
        await loadControl();
      } else {
        Message.error("反结账失败，请确认原因和当前权限");
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <Card
        style={{ borderRadius: 12 }}
        title="月结与会计期间"
        extra={activeControlLoading ? null : <Tag color={status.color}>{status.label}</Tag>}
      >
        {loading || activeControlLoading ? (
          <Skeleton />
        ) : (
          <div className="space-y-3">
            {tasks.map((task) => (
              <div key={task.label} className="flex items-start gap-3 rounded-xl border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                <span
                  className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full"
                  style={{
                    backgroundColor: task.unavailable
                      ? "var(--color-fill-2)"
                      : task.done ? "rgba(16, 185, 129, 0.14)" : "var(--color-warning-soft)",
                    color: task.unavailable
                      ? "var(--text-color-3)"
                      : task.done ? "var(--color-success)" : "var(--color-warning)",
                  }}
                >
                  {task.done ? <IconCheckCircle /> : <IconExclamationCircle />}
                </span>
                <div className="min-w-0">
                  <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{task.label}</div>
                  <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{task.detail}</div>
                </div>
              </div>
            ))}

            <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <div className="flex items-center gap-2 font-semibold" style={{ color: "var(--text-color-1)" }}>
                    <IconLock /> 会计账期控制
                  </div>
                  <div className="mt-1 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
                    {activeControl ? `${actionLabel[activeControl.lastAction]} · 版本 ${activeControl.version}` : "状态暂不可用"}
                  </div>
                  {activeControl?.lastActionReason ? (
                    <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>反结账原因：{activeControl.lastActionReason}</div>
                  ) : null}
                </div>
                {canManage && activeControl ? (
                  <div className="flex flex-wrap gap-2">
                    <Button size="small" type="primary" onClick={() => setCloseVisible(true)}>执行关账</Button>
                    {activeControl.closedThrough ? (
                      <Button size="small" status="warning" onClick={() => setReopenVisible(true)}>反结账</Button>
                    ) : null}
                    <Button size="small" icon={<IconRefresh />} onClick={() => void loadControl()}>刷新</Button>
                  </div>
                ) : null}
              </div>
              {!tasksReady ? (
                <Alert className="mt-3" type="warning" content="月结清单仍有未完成项；关账后对应月份流水将禁止新增、修改、删除和退款。" />
              ) : null}
              {!canManage ? (
                <div className="mt-3 text-xs" style={{ color: "var(--text-color-3)" }}>仅创始人或财务管理员可以关账与反结账。</div>
              ) : null}
            </div>
          </div>
        )}
      </Card>

      <Modal
        title="执行会计期间关账"
        visible={closeVisible}
        confirmLoading={submitting}
        okButtonProps={{ disabled: !targetValid }}
        onOk={() => void closePeriod()}
        onCancel={() => setCloseVisible(false)}
      >
        <div className="space-y-3">
          <Alert type="warning" content="关账后，目标月份及更早日期的经营流水均不可新增、修改、删除或退款。" />
          <label className="block text-sm font-medium" htmlFor="accounting-close-month">关闭至月份</label>
          <input
            id="accounting-close-month"
            className="w-full rounded-md border px-3 py-2"
            style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-card)" }}
            type="month"
            max={previousMonth()}
            value={targetMonth}
            onChange={(event) => setTargetMonth(event.target.value)}
          />
          {!targetValid ? <div className="text-xs" style={{ color: "var(--color-danger)" }}>请选择尚未关闭的已完成月份。</div> : null}
        </div>
      </Modal>

      <Modal
        title="反结账"
        visible={reopenVisible}
        confirmLoading={submitting}
        okButtonProps={{ disabled: reopenReason.trim().length < 5 }}
        onOk={() => void reopenPeriod()}
        onCancel={() => setReopenVisible(false)}
      >
        <Alert type="warning" content="反结账会重新开放全部历史月份。请完成更正后再次关账，操作原因会写入审计日志。" />
        <div className="mt-4 text-sm font-medium">反结账原因</div>
        <Input.TextArea
          className="mt-2"
          value={reopenReason}
          onChange={setReopenReason}
          rows={4}
          maxLength={500}
          showWordLimit
          placeholder="至少 5 个字符，例如：补录银行回单并重新核对余额"
        />
      </Modal>
    </>
  );
}
