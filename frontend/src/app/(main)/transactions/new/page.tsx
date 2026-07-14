"use client";

import { useEffect, useMemo } from "react";
import { Button, Card, Spin } from "@arco-design/web-react";
import { IconSwap } from "@arco-design/web-react/icon";
import { useRouter, useSearchParams } from "next/navigation";
import PageHeader from "@/components/common/PageHeader";

const validId = (value: string | null) => {
  if (!value) return null;
  const id = Number(value);
  return Number.isInteger(id) && id > 0 ? id : null;
};

export default function NewTransactionPage() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const target = useMemo(() => {
    const refundValue = searchParams.get("refund");
    const editValue = searchParams.get("edit");
    const refundId = validId(refundValue);
    const editId = validId(editValue);

    if (refundValue !== null) {
      return refundId ? `/transactions?action=refund&transactionId=${refundId}` : "/transactions";
    }
    if (editValue !== null) {
      return editId ? `/transactions?action=edit&transactionId=${editId}` : "/transactions";
    }
    return "/transactions?action=new";
  }, [searchParams]);

  useEffect(() => {
    router.replace(target, { scroll: false });
  }, [router, target]);

  return (
    <div className="mx-auto w-full max-w-xl animate-fade-in">
      <PageHeader title="正在打开流水表单" subtitle="交易录入已统一到经营流水页面" icon={<IconSwap />} back />
      <Card style={{ borderRadius: 16 }}>
        <div className="flex flex-col items-center px-4 py-10 text-center" role="status" aria-live="polite">
          <Spin size={32} />
          <div className="mt-5 font-semibold" style={{ color: "var(--text-color-1)" }}>正在前往统一交易入口</div>
          <div className="mt-2 text-sm leading-6" style={{ color: "var(--text-color-3)" }}>
            新增、编辑和退款现在共用同一套校验与提交反馈，避免两套表单产生不一致记录。
          </div>
          <Button type="primary" className="mt-5" onClick={() => router.replace(target, { scroll: false })}>
            立即打开
          </Button>
        </div>
      </Card>
    </div>
  );
}
