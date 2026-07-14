import type { ReactNode } from "react";
import { IconCheckCircle, IconLock, IconSafe, IconStorage } from "@arco-design/web-react/icon";

type AuthShellProps = {
  variant: "login" | "register";
  title: string;
  description: string;
  children: ReactNode;
};

const content = {
  login: {
    eyebrow: "Mamoji Business OS",
    headline: "把经营数据，变成今天就能执行的判断。",
    copy: "收入、资金、票据、税务和人员信息在同一工作台中保持清晰、可追踪。",
  },
  register: {
    eyebrow: "Create your workspace",
    headline: "从第一天开始，建立可信的经营底账。",
    copy: "用统一主体、权限和数据口径组织团队日常经营，减少表格之间的反复核对。",
  },
};

const capabilities = [
  { icon: <IconStorage />, label: "经营与资金一体化" },
  { icon: <IconSafe />, label: "票据与合规可追踪" },
  { icon: <IconLock />, label: "主体和权限有边界" },
];

export default function AuthShell({ variant, title, description, children }: AuthShellProps) {
  const copy = content[variant];

  return (
    <main className="auth-shell">
      <aside className="auth-showcase" aria-label="Mamoji">
        <div className="auth-showcase-grid" aria-hidden="true" />
        <div className="auth-brand">
          <span className="auth-brand-mark">M</span>
          <span>
            <strong>Mamoji</strong>
            <small>企业经营工作台</small>
          </span>
        </div>

        <div className="auth-showcase-content">
          <div className="auth-eyebrow">{copy.eyebrow}</div>
          <h1>{copy.headline}</h1>
          <p>{copy.copy}</p>
          <div className="auth-capability-list">
            {capabilities.map((item) => (
              <div key={item.label} className="auth-capability-item">
                <span>{item.icon}</span>
                <span>{item.label}</span>
                <IconCheckCircle />
              </div>
            ))}
          </div>
        </div>

        <div className="auth-trust-note">
          <span className="auth-status-dot" />
          <span>会话过期与生产安全配置均由系统自动校验</span>
        </div>
      </aside>

      <section className="auth-form-side">
        <div className="auth-mobile-brand">
          <span className="auth-brand-mark">M</span>
          <strong>Mamoji</strong>
        </div>
        <div className="auth-form-card">
          <header className="auth-form-header">
            <div className="auth-eyebrow">{variant === "login" ? "Welcome back" : "Get started"}</div>
            <h2>{title}</h2>
            <p>{description}</p>
          </header>
          {children}
        </div>
      </section>
    </main>
  );
}
