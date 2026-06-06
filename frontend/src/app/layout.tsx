import type { Metadata } from "next";
import { NextIntlClientProvider } from "next-intl";
import { getLocale, getMessages } from "next-intl/server";
import ArcoReact19Adapter from "./ArcoReact19Adapter";
import "./globals.css";

const themeBootScript = `
(() => {
  try {
    var theme = localStorage.getItem('theme');
    if (theme !== 'dark' && theme !== 'light') theme = 'light';
    var root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.classList.toggle('dark', theme === 'dark');
    root.style.colorScheme = theme;
  } catch (error) {}
})();
`;

export const metadata: Metadata = {
  title: "Mamoji - 企业经营助手",
  description: "初创公司经营记账、人员管理、预算控制和税费分析系统",
};

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const locale = await getLocale();
  const messages = await getMessages();

  return (
    <html lang={locale} data-theme="light" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeBootScript }} />
      </head>
      <body>
        <ArcoReact19Adapter />
        <NextIntlClientProvider messages={messages}>
          {children}
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
