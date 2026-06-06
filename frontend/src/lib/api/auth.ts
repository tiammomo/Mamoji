import client from "./client";
import type { LoginDTO, RegisterDTO, User, UpdateProfileDTO, ChangePasswordDTO } from "@/lib/types";

export const authApi = {
  login: (data: LoginDTO) => client.post<{ token: string; user: User }>("/auth/login", data),
  register: (data: RegisterDTO) => client.post<{ token: string; user: User }>("/auth/register", data),
  me: () => client.get<User>("/auth/me"),
  updateProfile: (data: UpdateProfileDTO) => client.put<User>("/auth/profile", data),
  changePassword: (data: ChangePasswordDTO) => client.put("/auth/password", data),
};
