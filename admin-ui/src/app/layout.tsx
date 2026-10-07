import "./globals.css";
import type { ReactNode } from "react";

export const metadata = { title: "AI 콘텐츠 플랫폼 관리자" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
