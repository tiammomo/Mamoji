"use client";

import { Pagination } from "@arco-design/web-react";

type AppPaginationProps = {
  current: number;
  pageSize: number;
  total: number;
  onChange: (page: number, pageSize: number) => void;
  pageSizeOptions?: number[];
  hideOnSinglePage?: boolean;
  className?: string;
};

export default function AppPagination({
  current,
  pageSize,
  total,
  onChange,
  pageSizeOptions = [10, 20, 50, 100],
  hideOnSinglePage = true,
  className = "",
}: AppPaginationProps) {
  if (total <= 0) return null;
  if (hideOnSinglePage && total <= pageSize) return null;

  return (
    <div className={`app-pagination ${className}`}>
      <Pagination
        current={current}
        pageSize={pageSize}
        total={total}
        size="small"
        sizeCanChange
        showJumper
        hideOnSinglePage={hideOnSinglePage}
        pageSizeChangeResetCurrent
        sizeOptions={pageSizeOptions}
        showTotal={(count, range) => `${range[0]}-${range[1]} / 共 ${count} 条`}
        onChange={onChange}
      />
    </div>
  );
}
