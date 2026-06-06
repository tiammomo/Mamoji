export const validationRules = {
  required: (msg: string) => ({ required: true, message: msg }),
  email: { type: "email" as const, message: "请输入有效的邮箱地址" },
  amount: [
    { required: true, message: "请输入金额" },
    {
      validator: (_: unknown, value: number) => {
        if (value <= 0) return Promise.reject("金额必须大于0");
        if (value > 10000000) return Promise.reject("金额不能超过10,000,000");
        if (!/^\d+(\.\d{1,2})?$/.test(String(value))) return Promise.reject("最多两位小数");
        return Promise.resolve();
      },
    },
  ],
  dateNotFuture: {
    validator: (_: unknown, value: string) => {
      if (new Date(value) > new Date()) return Promise.reject("日期不能是未来日期");
      return Promise.resolve();
    },
  },
  dateNotTooOld: {
    validator: (_: unknown, value: string) => {
      const min = new Date();
      min.setFullYear(min.getFullYear() - 20);
      if (new Date(value) < min) return Promise.reject("日期不能早于20年前");
      return Promise.resolve();
    },
  },
  note: { maxLength: 200, message: "备注最多200个字符" },
  name: { maxLength: 64, message: "名称最多64个字符" },
};
