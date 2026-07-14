import client from "./client";

export interface BackupStatus {
  users: number;
  accounts: number;
  categories: number;
  transactions: number;
  budgets: number;
  ledgers: number;
  employees: number;
  taxItems: number;
  receipts: number;
  payrollRuns: number;
  notifications: number;
  datasets: number;
}

export interface BackupValidation {
  valid: boolean;
  restorable: boolean;
  dryRun?: boolean;
  format?: string;
  version?: string;
  message: string;
  counts?: Record<string, number>;
  checksum?: string;
  attachmentBytesIncluded?: boolean;
}

export const backupApi = {
  status: () => client.get<BackupStatus>("/backup/status"),
  export: () => client.get("/backup/export", { responseType: "blob" }),
  validate: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return client.post<BackupValidation>("/backup/validate", form);
  },
  restore: (file: File, options: { confirmation?: string; dryRun?: boolean } = {}) => {
    const form = new FormData();
    form.append("file", file);
    const params = {
      confirmation: options.confirmation || "",
      dryRun: options.dryRun ?? true,
    };
    return client.post<BackupValidation & { restored?: boolean; restoredAt?: string }>("/backup/restore", form, { params });
  },
};
