import client from "./client";

export type GlobalSearchResult = {
  type: "transaction" | "receipt" | "account" | "tax" | "employee";
  id: number;
  title: string;
  subtitle: string;
  path: string;
};

export type GlobalSearchResponse = {
  keyword: string;
  results: GlobalSearchResult[];
};

export const globalSearchApi = {
  search: (keyword: string, limit = 5, signal?: AbortSignal) =>
    client.get<GlobalSearchResponse>("/search", { params: { keyword, limit }, signal }),
};
