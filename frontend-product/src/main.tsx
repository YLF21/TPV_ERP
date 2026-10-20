import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./base.css";
import "./marketing.css";
import "./compact-pages.css";
import "./compact-contact.css";
import "./service-information.css";
createRoot(document.getElementById("root")!).render(
  <React.StrictMode><App /></React.StrictMode>
);
