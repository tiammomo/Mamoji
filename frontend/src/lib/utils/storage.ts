export const storage = {
  get<T>(key: string, fallback: T): T {
    if (typeof window === "undefined") return fallback;
    try {
      const val = localStorage.getItem(key);
      return val ? JSON.parse(val) : fallback;
    } catch {
      return fallback;
    }
  },
  set(key: string, value: unknown) {
    if (typeof window === "undefined") return;
    localStorage.setItem(key, JSON.stringify(value));
  },
  remove(key: string) {
    if (typeof window === "undefined") return;
    localStorage.removeItem(key);
  },
};
