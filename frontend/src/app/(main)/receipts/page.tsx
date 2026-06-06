"use client";
import { Card, Upload, Message, Empty } from "@arco-design/web-react";
import { useTranslations } from "next-intl";
import PageHeader from "@/components/common/PageHeader";

export default function ReceiptsPage() {
  const t = useTranslations("nav");
  const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:38080/api/v1";

  return (
    <div className="max-w-6xl mx-auto">
      <PageHeader title={t("receipts")} />

      <Card className="mb-4">
        <Upload
          drag
          accept="image/*"
          multiple={false}
          showUploadList={false}
          action={`${apiBase}/receipts/upload`}
          headers={{
            Authorization: `Bearer ${typeof window !== "undefined" ? localStorage.getItem("token") || "" : ""}`,
          }}
          onChange={(fileList) => {
            const file = fileList[fileList.length - 1];
            if (file?.status === "done") {
              Message.success("上传成功");
            } else if (file?.status === "error") {
              Message.error("上传失败");
            }
          }}
        >
          <div className="py-8 text-center">
            <div className="text-4xl mb-2">📷</div>
            <div className="text-sm" style={{ color: "var(--text-color-2)" }}>
              拖拽图片到此处或点击上传
            </div>
            <div className="text-xs mt-1" style={{ color: "var(--text-color-3)" }}>
              支持 JPG、PNG 格式
            </div>
          </div>
        </Upload>
      </Card>

      <Card>
        <Empty description="暂无收据" />
      </Card>
    </div>
  );
}
