import { StrictMode, Suspense } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./main";

// Keep mounting outside the component module so Fast Refresh only updates the tree.
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Suspense fallback={null}>
      <App />
    </Suspense>
  </StrictMode>
);
