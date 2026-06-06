"use client";
import { useEffect, useState } from "react";
import { Table, Button, Modal, Form, Input, Select, DatePicker, Switch, Message, Tag } from "@arco-design/web-react";
import { IconPlus, IconDelete, IconPlayArrow } from "@arco-design/web-react/icon";
import { useTranslations } from "next-intl";
import { recurringApi } from "@/lib/api/recurring";
import type { RecurringItem, CreateRecurringDTO } from "@/lib/api/recurring";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { formatDate } from "@/lib/utils/format";

const FormItem = Form.Item;

export default function RecurringPage() {
  const t = useTranslations("recurring");
  const [data, setData] = useState<RecurringItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [form] = Form.useForm();
  const recurringPagination = useClientPagination(data, 10);

  const fetchData = async () => {
    try {
      const res = await recurringApi.list();
      setData(res.data);
    } catch {
      // silent
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let cancelled = false;

    const loadRecurring = async () => {
      try {
        const res = await recurringApi.list();
        if (!cancelled) {
          setData(res.data);
        }
      } catch {
        // silent
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void loadRecurring();

    return () => {
      cancelled = true;
    };
  }, []);

  const handleSubmit = async (values: CreateRecurringDTO) => {
    try {
      await recurringApi.create(values);
      Message.success("创建成功");
      setModalVisible(false);
      form.resetFields();
      fetchData();
    } catch {
      Message.error("创建失败");
    }
  };

  const handleToggle = async (id: string) => {
    try {
      await recurringApi.toggle(id);
      fetchData();
    } catch {
      Message.error("操作失败");
    }
  };

  const handleExecute = async (id: string) => {
    try {
      await recurringApi.execute(id);
      Message.success("执行成功");
    } catch {
      Message.error("执行失败");
    }
  };

  const handleDelete = (id: string) => {
    Modal.confirm({
      title: "确认删除",
      content: "确定要删除这个周期事项吗？",
      onOk: async () => {
        try {
          await recurringApi.delete(id);
          Message.success("删除成功");
          fetchData();
        } catch {
          Message.error("删除失败");
        }
      },
    });
  };

  const frequencyLabels: Record<string, string> = {
    daily: t("daily"),
    weekly: t("weekly"),
    monthly: t("monthly"),
    yearly: t("yearly"),
  };

  const columns = [
    { title: t("name"), dataIndex: "name" },
    {
      title: "类型",
      dataIndex: "type",
      render: (type: number) => (
        <Tag color={type === 1 ? "green" : "red"}>{type === 1 ? "收入" : "成本支出"}</Tag>
      ),
    },
    {
      title: "金额",
      dataIndex: "amount",
      render: (amount: number, record: RecurringItem) => (
        <AmountDisplay amount={amount} type={record.type} />
      ),
    },
    {
      title: t("frequency"),
      dataIndex: "frequency",
      render: (f: string) => frequencyLabels[f] || f,
    },
    {
      title: t("nextExecution"),
      dataIndex: "nextExecution",
      render: (d: string) => formatDate(d),
    },
    {
      title: t("endDate"),
      dataIndex: "endDate",
      render: (d?: string) => (d ? formatDate(d) : "长期"),
    },
    {
      title: "状态",
      dataIndex: "status",
      render: (status: number, record: RecurringItem) => (
        <Switch
          checked={status === 1}
          onChange={() => handleToggle(record.id)}
        />
      ),
    },
    {
      title: "操作",
      width: 120,
      render: (_: unknown, record: RecurringItem) => (
        <div className="flex gap-1">
          <Button type="text" size="mini" icon={<IconPlayArrow />} onClick={() => handleExecute(record.id)} />
          <Button type="text" size="mini" status="danger" icon={<IconDelete />} onClick={() => handleDelete(record.id)} />
        </div>
      ),
    },
  ];

  return (
    <div className="max-w-6xl mx-auto">
      <PageHeader
        title={t("title")}
        extra={
          <Button type="primary" icon={<IconPlus />} onClick={() => setModalVisible(true)}>
            {t("new")}
          </Button>
        }
      />

      <Table
        columns={columns}
        data={recurringPagination.pagedData}
        loading={loading}
        rowKey="id"
        border={false}
        pagination={false}
      />
      <AppPagination
        current={recurringPagination.page}
        pageSize={recurringPagination.pageSize}
        total={recurringPagination.total}
        pageSizeOptions={[10, 20, 50, 100]}
        onChange={recurringPagination.handleChange}
      />

      <Modal
        title={t("new")}
        visible={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={() => form.submit()}
      >
        <Form form={form} layout="vertical" onSubmit={handleSubmit}>
          <FormItem label={t("name")} field="name" rules={[{ required: true }]}>
            <Input placeholder="名称" />
          </FormItem>
          <FormItem label="类型" field="type" rules={[{ required: true }]}>
            <Select>
              <Select.Option value={1}>收入</Select.Option>
              <Select.Option value={2}>成本支出</Select.Option>
            </Select>
          </FormItem>
          <FormItem label="金额" field="amount" rules={[{ required: true }]}>
            <Input type="number" placeholder="0.00" />
          </FormItem>
          <FormItem label={t("frequency")} field="frequency" rules={[{ required: true }]}>
            <Select>
              <Select.Option value="daily">{t("daily")}</Select.Option>
              <Select.Option value="weekly">{t("weekly")}</Select.Option>
              <Select.Option value="monthly">{t("monthly")}</Select.Option>
              <Select.Option value="yearly">{t("yearly")}</Select.Option>
            </Select>
          </FormItem>
          <FormItem label={t("interval")} field="interval" initialValue={1}>
            <Input type="number" min={1} />
          </FormItem>
          <FormItem label={t("startDate")} field="startDate" rules={[{ required: true }]}>
            <DatePicker className="w-full" />
          </FormItem>
          <FormItem label={t("endDate")} field="endDate">
            <DatePicker className="w-full" placeholder="不设置则长期有效" allowClear />
          </FormItem>
          <FormItem label="备注" field="note">
            <Input.TextArea placeholder="备注（可选）" />
          </FormItem>
        </Form>
      </Modal>
    </div>
  );
}
