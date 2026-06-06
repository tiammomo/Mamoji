export interface Category {
  id: number;
  name: string;
  type: "income" | "expense";
  icon: string;
  color: string;
  familyId: number | null;
  isSystem: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCategoryDTO {
  name: string;
  type: "income" | "expense";
  icon: string;
  color: string;
}

export interface UpdateCategoryDTO {
  name?: string;
  icon?: string;
  color?: string;
}
