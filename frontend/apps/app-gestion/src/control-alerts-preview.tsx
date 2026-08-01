import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createTranslator, type UserSession } from "@tpverp/app-common";
import { ControlAlertsScreen } from "./ControlAlertsScreen";
import "./gestion.css";

const t = createTranslator("es");
const session: UserSession = {
  username: "ADMIN",
  displayName: "ADMIN",
  accessToken: "design-preview",
  permissions: ["ADMIN"]
};

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <main style={{ padding: 24, background: "#e7ebef", minHeight: "100vh" }}>
      <ControlAlertsScreen session={session} t={t} />
    </main>
  </StrictMode>
);
