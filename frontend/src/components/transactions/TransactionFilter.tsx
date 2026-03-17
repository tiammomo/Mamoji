"use client";

/**
 * Stateless transaction filter bar used by transaction list page.
 */
interface TransactionFilterProps {
  searchKeyword: string;
  setSearchKeyword: (value: string) => void;
  typeFilter: number | null;
  setTypeFilter: (value: number | null) => void;
  dateRange: { start: string; end: string };
  setDateRange: (value: { start: string; end: string }) => void;
  showDateFilter: boolean;
  setShowDateFilter: (value: boolean) => void;
}

/**
 * Provides keyword, type and date-range filtering controls.
 */
export function TransactionFilter({
  searchKeyword,
  setSearchKeyword,
  typeFilter,
  setTypeFilter,
  dateRange,
  setDateRange,
  showDateFilter,
  setShowDateFilter,
}: TransactionFilterProps) {
  return (
    <div className="bg-white rounded-2xl shadow-sm p-4 mb-6">
      <div className="flex items-center gap-4 flex-wrap">
        <div className="relative flex-1 max-w-md">
          <svg
            className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
            />
          </svg>
          <input
            type="text"
            placeholder="搜索交易记录..."
            value={searchKeyword}
            onChange={(e) => setSearchKeyword(e.target.value)}
            className="w-full pl-10 pr-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
          />
        </div>

        <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z"
          />
        </svg>

        <div className="flex gap-2">
          <button
            onClick={() => setTypeFilter(null)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              typeFilter === null ? "bg-indigo-100 text-indigo-700" : "bg-gray-100 text-gray-600 hover:bg-gray-200"
            }`}
          >
            全部
          </button>
          <button
            onClick={() => setTypeFilter(1)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              typeFilter === 1 ? "bg-green-100 text-green-700" : "bg-gray-100 text-gray-600 hover:bg-gray-200"
            }`}
          >
            收入
          </button>
          <button
            onClick={() => setTypeFilter(2)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              typeFilter === 2 ? "bg-red-100 text-red-700" : "bg-gray-100 text-gray-600 hover:bg-gray-200"
            }`}
          >
            支出
          </button>
          <button
            onClick={() => setTypeFilter(3)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              typeFilter === 3 ? "bg-blue-100 text-blue-700" : "bg-gray-100 text-gray-600 hover:bg-gray-200"
            }`}
          >
            退款
          </button>
        </div>

        <div className="relative flex items-center gap-2">
          <button
            onClick={() => setShowDateFilter(!showDateFilter)}
            className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
              dateRange.start || dateRange.end
                ? "bg-indigo-100 text-indigo-700"
                : "bg-gray-100 text-gray-600 hover:bg-gray-200"
            }`}
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
              />
            </svg>
            {dateRange.start && dateRange.end ? `${dateRange.start} ~ ${dateRange.end}` : "日期筛选"}
            <svg
              className={`w-4 h-4 transition-transform ${showDateFilter ? "rotate-180" : ""}`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
            </svg>
          </button>

          {showDateFilter && (
            <div className="absolute top-full mt-2 p-4 bg-white rounded-xl shadow-lg border z-10">
              <div className="flex items-center gap-2">
                <input
                  type="date"
                  value={dateRange.start}
                  onChange={(e) => setDateRange({ ...dateRange, start: e.target.value })}
                  className="px-3 py-2 border border-gray-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
                <span className="text-gray-400">至</span>
                <input
                  type="date"
                  value={dateRange.end}
                  onChange={(e) => setDateRange({ ...dateRange, end: e.target.value })}
                  className="px-3 py-2 border border-gray-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>
              <div className="flex justify-end gap-2 mt-3">
                <button
                  onClick={() => {
                    setDateRange({ start: "", end: "" });
                    setShowDateFilter(false);
                  }}
                  className="px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 rounded-lg"
                >
                  清除
                </button>
                <button
                  onClick={() => setShowDateFilter(false)}
                  className="px-3 py-1.5 text-sm bg-indigo-600 text-white rounded-lg hover:bg-indigo-700"
                >
                  确定
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
