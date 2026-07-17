import client from "./client";
import type { AccessContext, AuthSession, LoginDTO, RegisterDTO, RegistrationInvite, User, UpdateProfileDTO, ChangePasswordDTO } from "@/lib/types";

export const authApi = {
  login: (data: LoginDTO) => client.post<AuthSession>("/auth/login", data),
  register: (data: RegisterDTO) => client.post<AuthSession>("/auth/register", data),
  invitations: () => client.get<RegistrationInvite[]>("/auth/invitations"),
  createInvitation: (data: { email: string; role?: number; permissions?: number; expiresInDays?: number }) =>
    client.post<RegistrationInvite>("/auth/invitations", data),
  me: () => client.get<User>("/auth/me"),
  accessContext: (params?: { companyId?: number }) => client.get<AccessContext>("/platform/access-context", { params }),
  logout: () => client.post<void>("/auth/logout"),
  updateProfile: (data: UpdateProfileDTO) => client.put<User>("/auth/profile", data),
  changePassword: (data: ChangePasswordDTO) => client.put("/auth/password", data),
};
