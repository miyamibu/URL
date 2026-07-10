import type { Metadata } from "next";
import "./styles.css";

export const metadata: Metadata = {
  title: "URL Saver Admin",
  description: "URL Saver promo code management",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ja">
      <body>{children}</body>
    </html>
  );
}
