"use client";

import { useEffect } from "react";
import { Button, Result } from "@arco-design/web-react";
import { IconRefresh } from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";

export default function MainError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const router = useRouter();

  useEffect(() => {
    console.error("Mamoji route error", error);
  }, [error]);

  return (
    <div className="error-state-card">
      <Result
        status="500"
        title="页面暂时无法显示"
        subTitle={`数据没有被修改。请重试；如问题持续，可向管理员提供错误编号 ${error.digest || "CLIENT"}。`}
        extra={
          <div className="flex flex-wrap justify-center gap-3">
            <Button type="primary" icon={<IconRefresh />} onClick={reset}>重新加载</Button>
            <Button onClick={() => router.push("/dashboard")}>返回工作台</Button>
          </div>
        }
      />
    </div>
  );
}
