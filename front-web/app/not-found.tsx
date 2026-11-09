import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Página no encontrada",
  description: "La página solicitada no existe en AgroCenter Digital.",
};

// Página 404 completamente estática: sin fetch, sin headers()/cookies() y sin
// estado de cliente. Así Next.js puede prerenderizarla en el build de Docker
// aunque el BFF o los microservicios no estén disponibles.
export default function NotFound() {
  return (
    <main
      className="shell section"
      style={{ paddingBlock: 96, textAlign: "center" }}
    >
      <p className="eyebrow">Error 404</p>
      <h1>No encontramos esta página</h1>
      <p role="status" style={{ marginBlock: 24 }}>
        Puede que el enlace haya cambiado o que la dirección esté incompleta.
        Revisa la URL o vuelve al inicio para continuar.
      </p>
      <Link className="solid-cta" href="/">
        Volver al inicio
      </Link>
    </main>
  );
}
