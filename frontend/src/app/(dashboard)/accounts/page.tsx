"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Banknote,
  Building2,
  CreditCard,
  DollarSign,
  Plus,
  Search,
  Trash2,
  TrendingDown,
  TrendingUp,
  Wallet,
  type LucideIcon,
} from "lucide-react";
import { accountApi, api, getErrorMessage, type Account } from "@/lib/api";

interface AccountSummary {
  totalAssets: number;
  totalLiabilities: number;
  netWorth: number;
}

interface NewAccountForm {
  name: string;
  type: string;
  subType: string;
  bank: string;
  balance: number;
  includeInNetWorth: boolean;
}

const TYPE_CONFIG: Record<string, { icon: LucideIcon; bgColor: string; textColor: string; label: string }> = {
  cash: { icon: Banknote, bgColor: "bg-green-100", textColor: "text-green-600", label: "现金" },
  bank: { icon: CreditCard, bgColor: "bg-blue-100", textColor: "text-blue-600", label: "银行卡" },
  credit: { icon: CreditCard, bgColor: "bg-red-100", textColor: "text-red-600", label: "信用卡" },
  digital: { icon: Wallet, bgColor: "bg-purple-100", textColor: "text-purple-600", label: "数字钱包" },
  investment: { icon: TrendingUp, bgColor: "bg-yellow-100", textColor: "text-yellow-600", label: "投资" },
  debt: { icon: TrendingDown, bgColor: "bg-orange-100", textColor: "text-orange-600", label: "负债" },
};

const CHINESE_BANKS = [
  "中国工商银行",
  "中国农业银行",
  "中国银行",
  "中国建设银行",
  "交通银行",
  "招商银行",
  "中国民生银行",
  "中国光大银行",
  "华夏银行",
  "兴业银行",
  "浦发银行",
  "平安银行",
  "浙商银行",
  "恒丰银行",
  "农村商业银行",
  "农村信用合作社",
  "其他银行",
];

const DIGITAL_WALLETS = [
  { value: "alipay", label: "支付宝" },
  { value: "wechat", label: "微信" },
];

const BANK_CARD_TYPES = [
  { value: "type1", label: "一类卡" },
  { value: "type2", label: "二类卡" },
];

const EMPTY_ACCOUNT: NewAccountForm = {
  name: "",
  type: "cash",
  subType: "",
  bank: "",
  balance: 0,
  includeInNetWorth: true,
};

export default function AccountsPage() {
  const router = useRouter();
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [summary, setSummary] = useState<AccountSummary>({ totalAssets: 0, totalLiabilities: 0, netWorth: 0 });
  const [loading, setLoading] = useState(true);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [showAddModal, setShowAddModal] = useState(false);
  const [newAccount, setNewAccount] = useState<NewAccountForm>(EMPTY_ACCOUNT);

  useEffect(() => {
    const token = localStorage.getItem("token");
    if (!token) {
      router.push("/login");
      return;
    }

    void Promise.all([fetchAccounts(), fetchSummary()]).finally(() => setLoading(false));
  }, [router]);

  async function fetchAccounts(): Promise<void> {
    try {
      const data = await accountApi.getAccounts();
      setAccounts(data);
    } catch (error) {
      console.error("获取账户失败:", error);
    }
  }

  async function fetchSummary(): Promise<void> {
    try {
      const data = await api.get<AccountSummary>("/accounts/summary");
      setSummary(data || { totalAssets: 0, totalLiabilities: 0, netWorth: 0 });
    } catch (error) {
      console.error("获取汇总失败:", error);
    }
  }

  const filteredAccounts = useMemo(() => {
    if (!searchKeyword) {
      return accounts;
    }

    const keyword = searchKeyword.toLowerCase();
    return accounts.filter(
      (account) =>
        account.name.toLowerCase().includes(keyword) ||
        Boolean(account.bank && account.bank.toLowerCase().includes(keyword))
    );
  }, [accounts, searchKeyword]);

  async function handleCreateAccount(): Promise<void> {
    try {
      const payload = {
        ...newAccount,
        bank: newAccount.type === "bank" || newAccount.type === "credit" ? newAccount.bank : undefined,
        subType: newAccount.type === "bank" || newAccount.type === "digital" ? newAccount.subType : undefined,
      };

      await accountApi.createAccount(payload);
      setShowAddModal(false);
      setNewAccount(EMPTY_ACCOUNT);
      await Promise.all([fetchAccounts(), fetchSummary()]);
    } catch (error) {
      alert(getErrorMessage(error, "创建账户失败"));
    }
  }

  async function handleDeleteAccount(id: number): Promise<void> {
    if (!confirm("确定要删除该账户吗？")) {
      return;
    }

    try {
      await accountApi.deleteAccount(id);
      await Promise.all([fetchAccounts(), fetchSummary()]);
    } catch (error) {
      alert(getErrorMessage(error, "删除账户失败"));
    }
  }

  function getAccountConfig(type: string) {
    return TYPE_CONFIG[type] || { icon: DollarSign, bgColor: "bg-gray-100", textColor: "text-gray-600", label: type };
  }

  function getAccountSubLabel(account: Account): string {
    if (account.type === "credit") {
      return "信用卡";
    }
    if (account.subType === "type1") {
      return "一类卡";
    }
    if (account.subType === "type2") {
      return "二类卡";
    }
    if (account.subType === "alipay") {
      return "支付宝";
    }
    if (account.subType === "wechat") {
      return "微信";
    }

    return getAccountConfig(account.type).label;
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="animate-spin rounded-full h-12 w-12 border-t-2 border-b-2 border-indigo-600" />
      </div>
    );
  }

  return (
    <div className="p-6 lg:p-8">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">账户管理</h1>
          <p className="text-gray-500 mt-1">管理你的所有账户</p>
        </div>
        <div className="flex items-center gap-4">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
            <input
              type="text"
              placeholder="搜索账户..."
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              className="pl-10 pr-4 py-2 border border-gray-200 rounded-lg w-52"
            />
          </div>
          <button
            onClick={() => setShowAddModal(true)}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700"
          >
            <Plus className="w-5 h-5" />
            添加账户
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
        <SummaryCard title="总资产" value={summary.totalAssets} className="border-green-500 text-green-600" />
        <SummaryCard title="总负债" value={summary.totalLiabilities} className="border-red-500 text-red-600" />
        <SummaryCard title="净资产" value={summary.netWorth} className="border-indigo-500 text-indigo-600" />
      </div>

      <div className="bg-white rounded-2xl shadow-sm">
        <div className="divide-y">
          {filteredAccounts.length === 0 ? (
            <div className="p-12 text-center">
              <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
                <Wallet className="w-8 h-8 text-gray-400" />
              </div>
              <p className="text-gray-500 mb-4">暂无账户</p>
              <button
                onClick={() => setShowAddModal(true)}
                className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700"
              >
                <Plus className="w-4 h-4" />
                添加账户
              </button>
            </div>
          ) : (
            filteredAccounts.map((account) => {
              const config = getAccountConfig(account.type);
              const Icon = config.icon;
              return (
                <div key={account.id} className="p-4 flex items-center justify-between hover:bg-gray-50 transition-colors">
                  <div className="flex items-center gap-4">
                    <div className={`w-12 h-12 rounded-full flex items-center justify-center ${config.bgColor}`}>
                      <Icon className={`w-6 h-6 ${config.textColor}`} />
                    </div>
                    <div>
                      <p className="font-medium text-gray-900">{account.name}</p>
                      <p className="text-sm text-gray-500">
                        {account.bank ? `${account.bank} | ` : ""}
                        {getAccountSubLabel(account)}
                      </p>
                    </div>
                  </div>

                  <div className="flex items-center gap-4">
                    <div className="text-right">
                      <p className={`text-lg font-semibold ${account.balance < 0 ? "text-red-600" : "text-gray-900"}`}>
                        ¥{account.balance.toLocaleString("zh-CN", { minimumFractionDigits: 2 })}
                      </p>
                    </div>
                    <button
                      onClick={() => void handleDeleteAccount(account.id)}
                      className="p-2 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded-lg"
                    >
                      <Trash2 className="w-5 h-5" />
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>

      {showAddModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl p-6 w-full max-w-md mx-4">
            <h2 className="text-xl font-bold mb-4">添加账户</h2>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">账户名称</label>
                <input
                  type="text"
                  value={newAccount.name}
                  onChange={(e) => setNewAccount((prev) => ({ ...prev, name: e.target.value }))}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg"
                  placeholder="例如：我的银行卡"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">账户类型</label>
                <select
                  value={newAccount.type}
                  onChange={(e) => setNewAccount((prev) => ({ ...prev, type: e.target.value, subType: "", bank: "" }))}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg"
                >
                  <option value="cash">现金</option>
                  <option value="bank">银行卡</option>
                  <option value="credit">信用卡</option>
                  <option value="digital">数字钱包</option>
                  <option value="investment">投资</option>
                  <option value="debt">负债</option>
                </select>
              </div>

              {(newAccount.type === "bank" || newAccount.type === "credit") && (
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">选择银行</label>
                  <select
                    value={newAccount.bank}
                    onChange={(e) => setNewAccount((prev) => ({ ...prev, bank: e.target.value }))}
                    className="w-full px-4 py-2 border border-gray-200 rounded-lg"
                  >
                    <option value="">请选择银行</option>
                    {CHINESE_BANKS.map((bank) => (
                      <option key={bank} value={bank}>
                        {bank}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              {newAccount.type === "digital" && (
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">数字钱包类型</label>
                  <select
                    value={newAccount.subType}
                    onChange={(e) => setNewAccount((prev) => ({ ...prev, subType: e.target.value }))}
                    className="w-full px-4 py-2 border border-gray-200 rounded-lg"
                  >
                    <option value="">请选择</option>
                    {DIGITAL_WALLETS.map((wallet) => (
                      <option key={wallet.value} value={wallet.value}>
                        {wallet.label}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              {newAccount.type === "bank" && (
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">卡类型</label>
                  <select
                    value={newAccount.subType}
                    onChange={(e) => setNewAccount((prev) => ({ ...prev, subType: e.target.value }))}
                    className="w-full px-4 py-2 border border-gray-200 rounded-lg"
                  >
                    <option value="">请选择</option>
                    {BANK_CARD_TYPES.map((type) => (
                      <option key={type.value} value={type.value}>
                        {type.label}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">初始余额</label>
                <input
                  type="number"
                  step="0.01"
                  value={newAccount.balance}
                  onChange={(e) => setNewAccount((prev) => ({ ...prev, balance: Number.parseFloat(e.target.value) || 0 }))}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg"
                />
              </div>

              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="includeNetWorth"
                  checked={newAccount.includeInNetWorth}
                  onChange={(e) => setNewAccount((prev) => ({ ...prev, includeInNetWorth: e.target.checked }))}
                  className="w-4 h-4"
                />
                <label htmlFor="includeNetWorth" className="text-sm text-gray-700">
                  计入净资产
                </label>
              </div>
            </div>

            <div className="flex gap-4 mt-6">
              <button
                onClick={() => setShowAddModal(false)}
                className="flex-1 px-4 py-2 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200"
              >
                取消
              </button>
              <button
                onClick={() => void handleCreateAccount()}
                disabled={!newAccount.name || ((newAccount.type === "bank" || newAccount.type === "credit") && !newAccount.bank)}
                className="flex-1 px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:opacity-50"
              >
                保存
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function SummaryCard({ title, value, className }: { title: string; value: number; className: string }) {
  return (
    <div className={`bg-white rounded-2xl p-6 shadow-sm border-2 ${className}`}>
      <p className="text-gray-500 text-sm mb-3">{title}</p>
      <p className="text-2xl font-bold">¥{value.toLocaleString("zh-CN", { minimumFractionDigits: 2 })}</p>
    </div>
  );
}
