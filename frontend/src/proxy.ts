import { NextRequest, NextResponse } from "next/server";
import { locales, defaultLocale } from "./lib/i18n/config";

export function proxy(request: NextRequest) {
  const response = NextResponse.next();
  const locale = request.cookies.get("NEXT_LOCALE")?.value;

  if (!locales.includes(locale as (typeof locales)[number])) {
    response.cookies.set("NEXT_LOCALE", defaultLocale, {
      path: "/",
      sameSite: "lax",
    });
  }

  return response;
}

export const config = {
  matcher: ["/((?!api|_next|.*\\..*).*)"],
};
