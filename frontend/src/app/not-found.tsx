"use client";
import { Button, Result } from "@arco-design/web-react";
import { useRouter } from "next/navigation";

export default function NotFound() {
  const router = useRouter();

  return (
    <div className="system-state-page">
      <div className="system-state-brand" aria-hidden="true">M</div>
      <div className="error-state-card system-state-card">
        <Result
          status="404"
          title="找不到这个页面"
          subTitle="链接可能已失效，或当前账号没有对应入口。你可以返回工作台继续处理事项。"
          extra={
            <div className="flex flex-wrap justify-center gap-3">
              <Button type="primary" onClick={() => router.push("/dashboard")}>返回工作台</Button>
              <Button onClick={() => router.back()}>返回上一页</Button>
            </div>
          }
        />
      </div>
    </div>
  );
}
