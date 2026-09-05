export const problemCode = (error: unknown) => {
  if (!error || typeof error !== "object") return undefined;
  const response = "response" in error ? (error as { response?: { data?: unknown } }).response : undefined;
  const data = response?.data;
  if (!data || typeof data !== "object") return undefined;
  const code = (data as Record<string, unknown>).code;
  return typeof code === "string" ? code : undefined;
};
