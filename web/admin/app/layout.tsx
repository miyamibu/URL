import type { Metadata } from "next";
import "./styles.css";

export const metadata: Metadata = {
  title: "りんばむ 管理",
  description: "りんばむのサポート・モデレーション・権限管理",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ja">
      <body>{children}</body>
    </html>
  );
}
