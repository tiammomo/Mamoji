"use client";
import { useEffect, useState } from "react";
import { Card, Button, Grid, Statistic, Upload, Message, Alert } from "@arco-design/web-react";
import { IconDownload, IconUpload } from "@arco-design/web-react/icon";
import { useTranslations } from "next-intl";
import { backupApi } from "@/lib/api/backup";
import type { BackupStatus } from "@/lib/api/backup";
import PageHeader from "@/components/common/PageHeader";

const { Row, Col } = Grid;

export default function BackupPage() {
  const t = useTranslations("backup");
  const [status, setStatus] = useState<BackupStatus | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    backupApi.status()
      .then((r) => setStatus(r.data))
      .finally(() => setLoading(false));
  }, []);

  const handleExport = async () => {
    try {
      const res = await backupApi.export();
      const blob = new Blob([res.data], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `mamoji-backup-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      Message.success("导出成功");
    } catch {
      Message.error("导出失败");
    }
  };

  return (
    <div className="max-w-4xl mx-auto">
      <PageHeader title={t("title")} />

      <Card className="mb-4" title={t("status")} loading={loading}>
        {status && (
          <Row gutter={16}>
            <Col span={4}><Statistic title="用户" value={status.users} /></Col>
            <Col span={4}><Statistic title="账户" value={status.accounts} /></Col>
            <Col span={4}><Statistic title="分类" value={status.categories} /></Col>
            <Col span={4}><Statistic title="交易" value={status.transactions} /></Col>
            <Col span={4}><Statistic title="预算" value={status.budgets} /></Col>
            <Col span={4}><Statistic title="账本" value={status.ledgers} /></Col>
          </Row>
        )}
      </Card>

      <Row gutter={16}>
        <Col span={12}>
          <Card>
            <div className="text-center">
              <div className="text-4xl mb-4">📤</div>
              <h3 className="mb-2">{t("export")}</h3>
              <p className="text-sm mb-4" style={{ color: "var(--text-color-3)" }}>
                导出所有数据为 JSON 文件
              </p>
              <Button type="primary" icon={<IconDownload />} onClick={handleExport} long>
                {t("export")}
              </Button>
            </div>
          </Card>
        </Col>
        <Col span={12}>
          <Card>
            <div className="text-center">
              <div className="text-4xl mb-4">📥</div>
              <h3 className="mb-2">{t("import")}</h3>
              <p className="text-sm mb-4" style={{ color: "var(--text-color-3)" }}>
                从 JSON 文件导入数据
              </p>
              <Upload
                accept=".json,.zip"
                showUploadList={false}
                beforeUpload={async (file) => {
                  try {
                    const res = await backupApi.validate(file);
                    if (res.data.valid) {
                      Message.success("文件校验通过");
                    } else {
                      Message.warning(res.data.message);
                    }
                  } catch {
                    Message.error("校验失败");
                  }
                  return false;
                }}
              >
                <Button type="outline" icon={<IconUpload />} long>
                  {t("import")}
                </Button>
              </Upload>
              <Alert
                type="info"
                content="导入功能正在开发中"
                className="mt-4"
              />
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
