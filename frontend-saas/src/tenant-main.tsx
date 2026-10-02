import React from "react";
import { createRoot } from "react-dom/client";
import { TenantApp } from "./app/TenantApp";
import "./styles.css";

createRoot(document.getElementById("root")!).render(<React.StrictMode><TenantApp /></React.StrictMode>);
