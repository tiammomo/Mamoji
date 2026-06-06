export interface User {
  id: number;
  email: string;
  nickname: string;
  avatar: string; // "emoji|color" format
  familyId: number | null;
  role: number; // 1=admin, 2=user
  permissions: number; // bitmask: 1=user, 2=account, 4=category, 8=budget
  createdAt: string;
  updatedAt: string;
}

export interface LoginDTO {
  email: string;
  password: string;
}

export interface RegisterDTO {
  email: string;
  password: string;
  nickname: string;
  avatar?: string;
}

export interface UpdateProfileDTO {
  nickname?: string;
  avatar?: string;
}

export interface ChangePasswordDTO {
  oldPassword: string;
  newPassword: string;
}
