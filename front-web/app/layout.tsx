import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import "./corporate.css";
import PublicShell from "./components/PublicShell";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

// El sitio se exporta como estático (output: "export"), por lo que el layout raíz
// NO puede depender de `headers()`, `cookies()` ni de la request en build time:
// ese acceso dinámico rompe el prerender de /_not-found en el contenedor Docker.
// La URL pública se inyecta como variable de entorno en el build.
const siteUrl =
  process.env.NEXT_PUBLIC_SITE_URL?.trim() || "http://localhost:3000";

const metadataBase = (() => {
  try {
    return new URL(siteUrl);
  } catch {
    return new URL("http://localhost:3000");
  }
})();

export const metadata: Metadata = {
  metadataBase,
  title: { default: "AgroCenter Digital", template: "%s | AgroCenter Digital" },
  description: "Plataforma operativa para compras, ventas e inventario de AgroCenter.",
  openGraph: {
    title: "AgroCenter Digital",
    description: "Compras, ventas e inventario bajo control.",
    type: "website",
    images: [
      {
        url: "/og.png",
        width: 1734,
        height: 907,
        alt: "AgroCenter Digital",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "AgroCenter Digital",
    description: "Compras, ventas e inventario bajo control.",
    images: ["/og.png"],
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="es"><body className={`${geistSans.variable} ${geistMono.variable}`}><PublicShell>{children}</PublicShell></body></html>;
}
