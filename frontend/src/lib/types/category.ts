export interface Category {
  id: number;
  companyId: number;
  userId: number;
  name: string;
  type: "income" | "expense";
  icon: string;
  color: string;
  status: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCategoryDTO {
  companyId?: number;
  name: string;
  type: "income" | "expense";
  icon?: string;
  color?: string;
}

export interface UpdateCategoryDTO {
  companyId?: number;
  name?: string;
  type?: "income" | "expense";
  icon?: string;
  color?: string;
}
