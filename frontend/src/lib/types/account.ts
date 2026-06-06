export type AccountType = "cash" | "bank" | "credit" | "digital" | "investment" | "debt";

export interface Account {
  id: number;
  name: string;
  type: AccountType;
  subType: string | null;
  bank: string | null;
  balance: number;
  includeInNetWorth: boolean;
  userId: number;
  ledgerId: number | null;
  status: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateAccountDTO {
  name: string;
  type: AccountType;
  subType?: string;
  bank?: string;
  balance: number;
  includeInNetWorth?: boolean;
}

export interface UpdateAccountDTO {
  name?: string;
  type?: AccountType;
  subType?: string;
  bank?: string;
  balance?: number;
  includeInNetWorth?: boolean;
}

export interface AccountSummary {
  totalAssets: number;
  totalLiabilities: number;
  netWorth: number;
}
