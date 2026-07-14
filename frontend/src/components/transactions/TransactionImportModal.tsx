"use client";

import { useEffect, useState } from "react";
import { Button, Message, Modal, Switch, Tag, Upload } from "@arco-design/web-react";
import { IconCheckCircle, IconDownload, IconExclamationCircle, IconUpload } from "@arco-design/web-react/icon";
import { transactionApi, type TransactionImportResult } from "@/lib/api/transactions";
import { formatAmount } from "@/lib/utils/format";

type Props = {
  visible: boolean;
  onClose: () => void;
  onSuccess: () => void;
};

export default function TransactionImportModal({ visible, onClose, onSuccess }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<TransactionImportResult | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [importing, setImporting] = useState(false);
  const [skipDuplicates, setSkipDuplicates] = useState(true);
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    if (visible) return;
    const timer = window.setTimeout(() => {
      setFile(null);
      setPreview(null);
      setPreviewing(false);
      setImporting(false);
      setSkipDuplicates(true);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [visible]);

  const previewFile = async (nextFile: File) => {
    setFile(nextFile);
    setPreview(null);
    setPreviewing(true);
    try {
      const response = await transactionApi.previewImport(nextFile);
      setPreview(response.data);
      if (response.data.invalidRows > 0) Message.warning(`发现 ${response.data.invalidRows} 行需要修正`);
      else Message.success("CSV 预检通过");
    } catch {
      setFile(null);
      Message.error("CSV 预检失败，请确认模板、编码和数据格式");
    } finally {
      setPreviewing(false);
    }
  };

  const downloadTemplate = async () => {
    setDownloading(true);
    try {
      const response = await transactionApi.importTemplate();
      const url = URL.createObjectURL(response.data);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "mamoji-transaction-import.csv";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch {
      Message.error("模板下载失败");
    } finally {
      setDownloading(false);
    }
  };

  const commitImport = async () => {
    if (!file || !preview || preview.invalidRows > 0) return;
    setImporting(true);
    try {
      const response = await transactionApi.importCsv(file, skipDuplicates);
      if (!response.data.committed) {
        setPreview(response.data);
        Message.warning("文件内容已经变化或存在错误，请重新检查");
        return;
      }
      Message.success(`已导入 ${response.data.importedRows} 笔流水${response.data.skippedRows ? `，跳过 ${response.data.skippedRows} 笔重复记录` : ""}`);
      onSuccess();
      onClose();
    } catch {
      Message.error("批量导入失败，数据未写入");
    } finally {
      setImporting(false);
    }
  };

  return (
    <Modal
      title="批量导入经营流水"
      visible={visible}
      style={{ width: "min(980px, calc(100vw - 24px))" }}
      footer={null}
      maskClosable={!importing}
      closable={!importing}
      onCancel={onClose}
    >
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_260px]">
        <div className="min-w-0">
          <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <div className="text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>使用 UTF-8 CSV 批量录入</div>
                <div className="mt-1 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>必需列：日期、类型、金额、分类、账户；分类与账户可填写名称或 ID，单次最多 500 行。</div>
              </div>
              <Button size="small" icon={<IconDownload />} loading={downloading} onClick={() => void downloadTemplate()}>下载模板</Button>
            </div>
            <Upload
              className="mt-4 block"
              accept=".csv,text/csv"
              disabled={previewing || importing}
              showUploadList={false}
              beforeUpload={async (nextFile) => {
                await previewFile(nextFile);
                return false;
              }}
            >
              <Button type="primary" icon={<IconUpload />} loading={previewing} long>{previewing ? "正在解析与查重" : file ? "重新选择 CSV" : "选择 CSV 并预检"}</Button>
            </Upload>
            {file ? <div className="mt-2 truncate text-xs" title={file.name} style={{ color: "var(--text-color-3)" }}>{file.name}</div> : null}
          </div>

          {preview ? (
            <div className="mt-4 overflow-hidden rounded-xl border" style={{ borderColor: "var(--border-color-light)" }}>
              <div className="flex flex-wrap items-center gap-2 border-b px-3 py-2" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
                <Tag color="arcoblue">共 {preview.totalRows} 行</Tag>
                <Tag color="green">有效 {preview.validRows}</Tag>
                <Tag color={preview.invalidRows ? "red" : "gray"}>错误 {preview.invalidRows}</Tag>
                <Tag color={preview.duplicateRows ? "orange" : "gray"}>疑似重复 {preview.duplicateRows}</Tag>
              </div>
              <div className="max-h-[360px] overflow-auto">
                <table className="w-full min-w-[760px] border-collapse text-xs">
                  <thead className="sticky top-0 z-10" style={{ backgroundColor: "var(--bg-color-card)" }}>
                    <tr>{["行", "日期", "类型", "金额", "分类", "账户", "备注 / 校验"].map((label) => <th key={label} className="border-b px-3 py-2 text-left font-medium" style={{ borderColor: "var(--border-color-light)", color: "var(--text-color-2)" }}>{label}</th>)}</tr>
                  </thead>
                  <tbody>
                    {preview.rows.map((row) => (
                      <tr key={row.rowNumber} style={{ backgroundColor: row.errors.length ? "rgba(239, 68, 68, 0.045)" : row.duplicate ? "var(--color-warning-soft)" : undefined }}>
                        <td className="border-b px-3 py-2" style={{ borderColor: "var(--border-color-light)" }}>{row.rowNumber}</td>
                        <td className="border-b px-3 py-2" style={{ borderColor: "var(--border-color-light)" }}>{row.date || "--"}</td>
                        <td className="border-b px-3 py-2" style={{ borderColor: "var(--border-color-light)" }}><Tag size="small" color={row.type === 1 ? "green" : "red"}>{row.type === 1 ? "收入" : "支出"}</Tag></td>
                        <td className="border-b px-3 py-2 whitespace-nowrap" style={{ borderColor: "var(--border-color-light)" }}>{formatAmount(row.amount)}</td>
                        <td className="border-b px-3 py-2" style={{ borderColor: "var(--border-color-light)" }}>{row.categoryName || "--"}</td>
                        <td className="border-b px-3 py-2" style={{ borderColor: "var(--border-color-light)" }}>{row.accountName || "--"}</td>
                        <td className="border-b px-3 py-2" style={{ borderColor: "var(--border-color-light)" }}>
                          {row.errors.length ? <span style={{ color: "var(--color-danger)" }}>{row.errors.join("；")}</span> : row.duplicate ? <Tag size="small" color="orange">疑似重复</Tag> : <span className="line-clamp-2" style={{ color: "var(--text-color-3)" }}>{row.note || "--"}</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ) : null}
        </div>

        <div className="space-y-3">
          <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
            <div className="text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>导入策略</div>
            <div className="mt-4 flex items-center justify-between gap-3">
              <div>
                <div className="text-sm" style={{ color: "var(--text-color-2)" }}>跳过疑似重复</div>
                <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>按日期、类型、金额、分类、账户和备注识别</div>
              </div>
              <Switch checked={skipDuplicates} onChange={setSkipDuplicates} />
            </div>
          </div>
          <div className="rounded-xl border p-4 text-xs leading-5" style={{ borderColor: preview?.invalidRows ? "rgba(239, 68, 68, 0.28)" : "rgba(16, 185, 129, 0.26)", backgroundColor: preview?.invalidRows ? "rgba(239, 68, 68, 0.05)" : "rgba(16, 185, 129, 0.05)", color: "var(--text-color-2)" }}>
            <div className="flex items-start gap-2">
              {preview?.invalidRows ? <IconExclamationCircle className="mt-0.5 shrink-0" style={{ color: "var(--color-danger)" }} /> : <IconCheckCircle className="mt-0.5 shrink-0" style={{ color: "var(--color-success)" }} />}
              <span>{preview ? preview.invalidRows ? "请修正所有错误行后重新预检。" : "预检通过。确认后将在一个事务中写入流水并同步账户余额。" : "选择文件后会先执行格式、归属、金额、日期和重复校验，不会立即写入。"}</span>
            </div>
          </div>
          <Button type="primary" loading={importing} disabled={!preview || preview.invalidRows > 0 || preview.validRows === 0} onClick={() => void commitImport()} long>
            {importing ? "正在事务化导入" : `确认导入${preview ? ` ${skipDuplicates ? preview.validRows - preview.duplicateRows : preview.validRows} 笔` : ""}`}
          </Button>
          <Button disabled={importing} onClick={onClose} long>取消</Button>
        </div>
      </div>
    </Modal>
  );
}
