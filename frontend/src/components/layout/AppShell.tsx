"use client";
import { useEffect, useState } from "react";
import Sidebar from "./Sidebar";
import Header from "./Header";
import MobileNav from "./MobileNav";
import { useIsMobile } from "@/lib/hooks/useMediaQuery";
import { useAuthStore } from "@/lib/stores/authStore";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import { useAppStore } from "@/lib/stores/appStore";
import { usePathname, useRouter } from "next/navigation";
import { useLocale } from "next-intl";
import { Spin } from "@arco-design/web-react";

export default function AppShell({ children }: { children: React.ReactNode }) {
  const isMobile = useIsMobile();
  const router = useRouter();
  const pathname = usePathname();
  const locale = useLocale();
  const { isAuthenticated, loading, fetchCurrentUser, user } = useAuthStore();
  const { fetchCategories } = useCategoryStore();
  const hydratePreferences = useAppStore((state) => state.hydratePreferences);
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const activeSubjectType = useAppStore((state) => state.activeSubjectType);
  const [online, setOnline] = useState(true);

  useEffect(() => {
    fetchCurrentUser();
  }, [fetchCurrentUser]);

  useEffect(() => {
    if (!loading && !isAuthenticated) {
      const next = `${pathname}${typeof window !== "undefined" ? window.location.search : ""}`;
      router.replace(`/login?next=${encodeURIComponent(next)}`);
    }
  }, [loading, isAuthenticated, pathname, router]);

  useEffect(() => {
    if (isAuthenticated && activeCompanyId) {
      void fetchCategories();
    }
  }, [isAuthenticated, activeCompanyId, fetchCategories]);

  useEffect(() => {
    hydratePreferences(locale === "en" ? "en" : "zh");
  }, [hydratePreferences, locale]);

  useEffect(() => {
    const sync = () => setOnline(window.navigator.onLine);
    sync();
    window.addEventListener("online", sync);
    window.addEventListener("offline", sync);
    return () => {
      window.removeEventListener("online", sync);
      window.removeEventListener("offline", sync);
    };
  }, []);

  useEffect(() => {
    const companyOnlyPrefixes = ["/operations", "/finance", "/receipts", "/tax", "/policy-center", "/hr", "/admin"];
    if (activeSubjectType === "household" && companyOnlyPrefixes.some((prefix) => pathname.startsWith(prefix))) {
      router.replace("/dashboard");
      return;
    }
    if (user && user.role !== 1 && pathname.startsWith("/backup")) {
      router.replace("/dashboard");
    }
  }, [activeSubjectType, pathname, router, user]);

  if (loading) {
    return (
      <div className="app-boot-screen" role="status" aria-label="Mamoji loading">
        <div className="app-boot-mark">M</div>
        <Spin size={30} />
      </div>
    );
  }

  if (!isAuthenticated) {
    return null;
  }

  return (
    <div className="app-shell flex h-screen overflow-hidden">
      <a href="#main-content" className="skip-link">跳到主要内容</a>
      {!isMobile && <Sidebar />}
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <Header />
        {!online && (
          <div className="offline-banner" role="status" aria-live="polite">
            当前网络已断开；已加载内容仍可查看，请恢复网络后重试写入操作。
          </div>
        )}
        <main
          id="main-content"
          className="app-main flex-1 overflow-y-auto"
          style={{
            backgroundColor: "var(--bg-color-page)",
            paddingBottom: isMobile ? "calc(var(--mobile-nav-height) + env(safe-area-inset-bottom) + 24px)" : undefined,
          }}
        >
          <div key={activeCompanyId || "unscoped"} className="app-main-inner">
            {children}
          </div>
        </main>
      </div>
      {isMobile && <MobileNav />}
    </div>
  );
}
