import client from "./client";

export interface BackupStatus {
  users: number;
  accounts: number;
  categories: number;
  transactions: number;
  budgets: number;
  ledgers: number;
}

export const backupApi = {
  status: () => client.get<BackupStatus>("/backup/status"),
  export: () => client.get("/backup/export", { responseType: "blob" }),
  validate: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return client.post<{ valid: boolean; message: string }>("/backup/validate", form);
  },
};
