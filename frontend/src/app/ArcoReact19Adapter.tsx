"use client";

import "@arco-design/web-react/es/_util/react-19-adapter";

const REACT_19_ARCO_REF_WARNING =
  "Accessing element.ref was removed in React 19. ref is now a regular prop.";

if (typeof window !== "undefined") {
  const target = window as typeof window & { __mamojiArcoReact19ConsolePatch?: boolean };

  if (!target.__mamojiArcoReact19ConsolePatch) {
    const originalError = console.error.bind(console);

    console.error = (...args: unknown[]) => {
      const [firstArg] = args;
      if (typeof firstArg === "string" && firstArg.includes(REACT_19_ARCO_REF_WARNING)) {
        return;
      }
      originalError(...args);
    };

    target.__mamojiArcoReact19ConsolePatch = true;
  }
}

export default function ArcoReact19Adapter() {
  return null;
}
