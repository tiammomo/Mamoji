"use client";
import { Button, Result } from "@arco-design/web-react";
import { useRouter } from "next/navigation";

export default function NotFound() {
  const router = useRouter();

  return (
    <div className="flex items-center justify-center min-h-screen">
      <Result
        status="404"
        title="404"
        subTitle="页面不存在"
        extra={
          <Button type="primary" onClick={() => router.push("/dashboard")}>
            返回首页
          </Button>
        }
      />
    </div>
  );
}
