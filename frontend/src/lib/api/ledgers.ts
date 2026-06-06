import client from "./client";
import type { Ledger, LedgerMember, CreateLedgerDTO } from "@/lib/types";

export const ledgerApi = {
  list: () => client.get<Ledger[]>("/ledgers"),
  default: () => client.get<Ledger>("/ledgers/default"),
  get: (id: number) => client.get<Ledger>(`/ledgers/${id}`),
  create: (data: CreateLedgerDTO) => client.post<Ledger>("/ledgers", data),
  members: (id: number) => client.get<LedgerMember[]>(`/ledgers/${id}/members`),
  addMember: (id: number, userId: number, role: string) =>
    client.post(`/ledgers/${id}/members`, { userId, role }),
  removeMember: (id: number, userId: number) =>
    client.delete(`/ledgers/${id}/members/${userId}`),
};
