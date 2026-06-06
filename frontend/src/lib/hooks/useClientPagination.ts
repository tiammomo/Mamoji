import { useMemo, useState } from "react";

const clampPage = (page: number, total: number, pageSize: number) => {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  return Math.min(Math.max(1, page), totalPages);
};

export function useClientPagination<T>(items: T[], initialPageSize = 10) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(initialPageSize);
  const total = items.length;
  const safePage = clampPage(page, total, pageSize);

  const pagedData = useMemo(() => {
    const start = (safePage - 1) * pageSize;
    return items.slice(start, start + pageSize);
  }, [items, pageSize, safePage]);

  const handleChange = (nextPage: number, nextPageSize: number) => {
    setPageSize(nextPageSize);
    setPage(clampPage(nextPage, total, nextPageSize));
  };

  return {
    page: safePage,
    pageSize,
    total,
    pagedData,
    setPage,
    setPageSize,
    resetPage: () => setPage(1),
    handleChange,
  };
}
