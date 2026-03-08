import { API_BASE, api } from "../api.client";
import type { BackupImportResponse, BackupStatus } from "../api.types";

export const backupApi = {
  exportData: async () => {
    const token = localStorage.getItem("token");
    const response = await fetch(`${API_BASE}/backup/export`, {
      method: "GET",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) {
      throw new Error("导出失败，请重试");
    }

    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = `mamoji-backup-${Date.now()}.json`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(objectUrl);
  },
  importData: (file: File) => {
    const formData = new FormData();
    formData.append("file", file);
    return api.postForm<BackupImportResponse>("/backup/import", formData);
  },
  getStatus: () => api.get<BackupStatus>("/backup/status"),
};
