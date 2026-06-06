"use client";
import { useEffect } from "react";
import Sidebar from "./Sidebar";
import Header from "./Header";
import MobileNav from "./MobileNav";
import { useIsMobile } from "@/lib/hooks/useMediaQuery";
import { useAuthStore } from "@/lib/stores/authStore";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import { useAppStore } from "@/lib/stores/appStore";
import { useRouter } from "next/navigation";
import { Spin } from "@arco-design/web-react";

export default function AppShell({ children }: { children: React.ReactNode }) {
  const isMobile = useIsMobile();
  const router = useRouter();
  const { isAuthenticated, loading, fetchCurrentUser } = useAuthStore();
  const { fetchCategories } = useCategoryStore();

  useEffect(() => {
    fetchCurrentUser();
  }, [fetchCurrentUser]);

  useEffect(() => {
    if (!loading && !isAuthenticated) {
      router.push("/login");
    }
  }, [loading, isAuthenticated, router]);

  useEffect(() => {
    if (isAuthenticated) {
      fetchCategories();
    }
  }, [isAuthenticated, fetchCategories]);

  useEffect(() => {
    const savedTheme = localStorage.getItem("theme") as "light" | "dark" | null;
    if (savedTheme) {
      useAppStore.getState().setTheme(savedTheme);
    }
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-screen">
        <Spin size={40} />
      </div>
    );
  }

  if (!isAuthenticated) {
    return null;
  }

  return (
    <div className="flex h-screen overflow-hidden">
      {!isMobile && <Sidebar />}
      <div className="flex flex-col flex-1 overflow-hidden">
        <Header />
        <main
          className="flex-1 overflow-y-auto p-4 md:p-6"
          style={{
            backgroundColor: "var(--bg-color-page)",
            paddingBottom: isMobile ? "calc(var(--mobile-nav-height) + 24px)" : undefined,
          }}
        >
          {children}
        </main>
      </div>
      {isMobile && <MobileNav />}
    </div>
  );
}
