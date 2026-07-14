"use client";

import { Skeleton } from "@arco-design/web-react";

export default function MainLoading() {
  return (
    <div className="mx-auto max-w-7xl animate-fade-in" role="status" aria-label="页面加载中">
      <div className="mb-7 flex items-center justify-between gap-4">
        <div className="w-full max-w-md">
          <Skeleton text={{ rows: 2, width: [180, 320] }} animation />
        </div>
        <Skeleton image={{ shape: "square" }} animation />
      </div>
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {[1, 2, 3, 4].map((item) => (
          <div key={item} className="loading-card">
            <Skeleton text={{ rows: 3 }} animation />
          </div>
        ))}
      </div>
      <div className="loading-card mt-5 min-h-[320px]">
        <Skeleton text={{ rows: 8 }} animation />
      </div>
    </div>
  );
}
