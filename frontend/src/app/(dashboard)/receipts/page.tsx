'use client';

import { useState, useCallback, useEffect } from 'react';
import { useDropzone } from 'react-dropzone';
import { Upload, Download, Trash2, X } from 'lucide-react';
import { receiptApi, Receipt, getErrorMessage } from '@/lib/api';

const PAGE_SIZE = 20;

export default function ReceiptsPage() {
  const [receipts, setReceipts] = useState<Receipt[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [selectedReceipt, setSelectedReceipt] = useState<Receipt | null>(null);
  const [errorText, setErrorText] = useState('');

  const fetchReceipts = useCallback(async () => {
    setLoading(true);
    setErrorText('');
    try {
      const result = await receiptApi.getReceipts({ page, pageSize: PAGE_SIZE });
      setReceipts(result.list);
      setTotal(result.total);
    } catch (error) {
      const status = (error as { status?: number; code?: number })?.status ?? (error as { status?: number; code?: number })?.code;
      if (status === 404) {
        setErrorText('收据接口未启用（/api/v1/receipts 不存在）。请先实现后端收据模块。');
      } else {
        setErrorText(getErrorMessage(error, '获取收据失败，请稍后重试'));
      }
      setReceipts([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    fetchReceipts();
  }, [fetchReceipts]);

  const onDrop = useCallback(
    async (acceptedFiles: File[]) => {
      setUploading(true);
      setErrorText('');
      try {
        await Promise.all(acceptedFiles.map((file) => receiptApi.upload(file)));
        await fetchReceipts();
      } catch (error) {
        setErrorText(getErrorMessage(error, '上传失败，请稍后重试'));
      } finally {
        setUploading(false);
      }
    },
    [fetchReceipts]
  );

  const { getRootProps, getInputProps, isDragActive, open } = useDropzone({
    onDrop,
    accept: {
      'image/*': ['.jpeg', '.jpg', '.png', '.gif', '.webp'],
    },
    maxSize: 10 * 1024 * 1024,
    noClick: true,
    noKeyboard: true,
  });

  async function handleDelete(id: number) {
    if (!confirm('确定要删除这张收据吗？')) {
      return;
    }
    try {
      await receiptApi.deleteReceipt(id);
      await fetchReceipts();
    } catch (error) {
      setErrorText(getErrorMessage(error, '删除失败，请稍后重试'));
    }
  }

  function handleDownload(id: number) {
    receiptApi.downloadReceipt(id);
  }

  function formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="min-h-screen bg-gray-50 p-6">
      <div className="max-w-6xl mx-auto space-y-6">
        <h1 className="text-2xl font-bold">收据管理</h1>

        <div
          {...getRootProps()}
          className={`border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-colors ${
            isDragActive ? 'border-indigo-500 bg-indigo-50' : 'border-gray-300 hover:border-gray-400'
          }`}
        >
          <input {...getInputProps()} />
          <Upload className="mx-auto h-12 w-12 text-gray-400 mb-4" />
          {uploading ? (
            <p className="text-indigo-600">上传中...</p>
          ) : isDragActive ? (
            <p className="text-indigo-600">松开鼠标即可上传</p>
          ) : (
            <p className="text-gray-600">拖拽收据图片到这里，或点击选择文件</p>
          )}
          <p className="text-sm text-gray-400 mt-2">支持 JPEG/PNG/GIF/WebP，最大 10MB</p>
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              open();
            }}
            className="mt-4 inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700"
          >
            <Upload className="w-4 h-4" />
            选择文件
          </button>
        </div>

        <div className="bg-white rounded-lg shadow">
          {errorText && (
            <div className="mx-4 mt-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
              {errorText}
            </div>
          )}
          <div className="p-4 border-b flex items-center justify-between">
            <div>
              <h2 className="text-lg font-semibold">收据列表</h2>
              <p className="text-sm text-gray-500">共 {total} 条记录</p>
            </div>
            <div className="text-sm text-gray-500">
              第 {page} / {totalPages} 页
            </div>
          </div>

          {loading ? (
            <div className="p-8 text-center text-gray-500">加载中...</div>
          ) : receipts.length === 0 ? (
            <div className="p-8 text-center text-gray-500">暂无收据，先上传一张试试</div>
          ) : (
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4 p-4">
              {receipts.map((receipt) => (
                <div key={receipt.id} className="border rounded-lg overflow-hidden hover:shadow-md transition-shadow">
                  <button
                    className="aspect-square bg-gray-100 w-full"
                    onClick={() => setSelectedReceipt(receipt)}
                  >
                    <img
                      src={`/api/v1/receipts/${receipt.id}/download`}
                      alt={receipt.originalName}
                      className="w-full h-full object-cover"
                    />
                  </button>
                  <div className="p-3 space-y-2">
                    <p className="text-sm font-medium truncate" title={receipt.originalName}>
                      {receipt.originalName}
                    </p>
                    <p className="text-xs text-gray-500">{formatFileSize(receipt.fileSize)}</p>
                    <div className="flex gap-2">
                      <button
                        className="flex-1 text-xs py-1.5 rounded bg-indigo-50 text-indigo-600 hover:bg-indigo-100 flex items-center justify-center gap-1"
                        onClick={() => handleDownload(receipt.id)}
                      >
                        <Download className="w-3 h-3" /> 下载
                      </button>
                      <button
                        className="flex-1 text-xs py-1.5 rounded bg-red-50 text-red-600 hover:bg-red-100 flex items-center justify-center gap-1"
                        onClick={() => handleDelete(receipt.id)}
                      >
                        <Trash2 className="w-3 h-3" /> 删除
                      </button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          <div className="p-4 border-t flex justify-end gap-2">
            <button
              className="px-3 py-1.5 text-sm rounded border border-gray-200 disabled:opacity-50"
              disabled={page <= 1}
              onClick={() => setPage((prev) => prev - 1)}
            >
              上一页
            </button>
            <button
              className="px-3 py-1.5 text-sm rounded border border-gray-200 disabled:opacity-50"
              disabled={page >= totalPages}
              onClick={() => setPage((prev) => prev + 1)}
            >
              下一页
            </button>
          </div>
        </div>
      </div>

      {selectedReceipt && (
        <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={() => setSelectedReceipt(null)}>
          <div className="bg-white rounded-xl max-w-3xl w-full overflow-hidden" onClick={(e) => e.stopPropagation()}>
            <div className="p-3 border-b flex justify-end">
              <button onClick={() => setSelectedReceipt(null)} className="p-1 rounded hover:bg-gray-100">
                <X className="w-5 h-5" />
              </button>
            </div>
            <img
              src={`/api/v1/receipts/${selectedReceipt.id}/download`}
              alt={selectedReceipt.originalName}
              className="w-full max-h-[70vh] object-contain bg-gray-100"
            />
          </div>
        </div>
      )}
    </div>
  );
}
