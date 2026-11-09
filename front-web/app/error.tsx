"use client";

import { useEffect } from "react";
import Link from "next/link";

// El error boundary es un componente de cliente y no realiza peticiones de red
// durante el build. Solo reporta el error en el navegador.
export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("Error no controlado en la aplicación:", error);
  }, [error]);

  return (
    <main
      className="shell section"
      style={{ paddingBlock: 96, textAlign: "center" }}
    >
      <p className="eyebrow">Algo salió mal</p>
      <h1>No pudimos mostrar este contenido</h1>
      <p role="status" style={{ marginBlock: 24 }}>
        Ocurrió un problema inesperado. Puedes reintentar la carga o volver al
        inicio.
      </p>
      <div style={{ display: "flex", gap: 12, justifyContent: "center" }}>
        <button className="solid-cta" type="button" onClick={() => reset()}>
          Reintentar
        </button>
        <Link className="outline-cta" href="/">
          Volver al inicio
        </Link>
      </div>
      {error.digest && (
        <p style={{ marginTop: 24, fontSize: 12 }}>
          Referencia del error: <code>{error.digest}</code>
        </p>
      )}
    </main>
  );
}
