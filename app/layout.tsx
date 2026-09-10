import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "SonicBrief — Local Audio Summarizer",
  description:
    "Transcribe YouTube, Bilibili, and audio files locally, then create a structured Chinese summary.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <head>
        <meta name="codex-preview" content="development" />
      </head>
      <body>{children}</body>
    </html>
  );
}
