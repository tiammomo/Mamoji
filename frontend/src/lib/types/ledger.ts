export type LedgerRole = "owner" | "admin" | "editor" | "viewer";

export interface Ledger {
  id: number;
  name: string;
  description: string;
  currency: string;
  ownerId: number;
  isDefault: boolean;
  status: number;
  createdAt: string;
  updatedAt: string;
}

export interface LedgerMember {
  id: number;
  ledgerId: number;
  userId: number;
  role: LedgerRole;
  nickname?: string;
  avatar?: string;
  joinedAt: string;
}

export interface CreateLedgerDTO {
  name: string;
  description?: string;
  currency?: string;
}
