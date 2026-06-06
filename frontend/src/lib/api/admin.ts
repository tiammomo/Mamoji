import client from "./client";
import type { User } from "@/lib/types";

export const adminApi = {
  listUsers: (params?: { keyword?: string; page?: number; size?: number }) =>
    client.get<{ content: User[]; totalElements: number }>("/admin/users", { params }),
  updateUser: (id: number, data: { role?: number; permissions?: number }) =>
    client.put<User>(`/admin/users/${id}`, data),
  deleteUser: (id: number) => client.delete(`/admin/users/${id}`),
};
