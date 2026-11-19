import { ArrowRight, ChatsCircle, Package, Storefront } from "@phosphor-icons/react";
import type { FormEventHandler } from "react";
import { useI18n } from "../../i18n";
import type { Notice } from "../../shared/types";
import { PortalLoginLayout } from "./PortalLoginLayout";

const content = {
  es: {
    portal: "Portal de clientes", kicker: "TU TIENDA, CONECTADA", title: "Más cerca de", accent: "tu negocio.",
    body: "Consulta la información sincronizada de tus tiendas y mantén el contacto con nuestro equipo.",
    features: ["Tus tiendas, en un mismo lugar", "Documentos y existencias", "Seguimiento de tus consultas"],
    permissions: "Las opciones disponibles dependen de los permisos de tu cuenta.",
    welcome: "Te damos la bienvenida", signIn: "Accede a tu cuenta", help: "Utiliza las credenciales facilitadas para tu tienda.",
    username: "Usuario de tienda", usernamePlaceholder: "Tu usuario", password: "Contraseña", passwordPlaceholder: "Tu contraseña",
    enter: "Entrar al portal", entering: "Accediendo…", accountHelp: "Si todavía no tienes acceso, contacta con el equipo de esPOS."
  },
  en: {
    portal: "Customer portal", kicker: "YOUR CONNECTED STORE", title: "Closer to", accent: "your business.",
    body: "View synchronised information from your stores and stay in touch with our team.",
    features: ["Your stores, in one place", "Documents and stock", "Follow up your enquiries"],
    permissions: "Available options depend on your account permissions.",
    welcome: "Welcome back", signIn: "Sign in to your account", help: "Use the credentials provided for your store.",
    username: "Store username", usernamePlaceholder: "Your username", password: "Password", passwordPlaceholder: "Your password",
    enter: "Sign in to the portal", entering: "Signing in…", accountHelp: "If you do not have access yet, contact the esPOS team."
  },
  zh: {
    portal: "客户门户", kicker: "连接你的门店", title: "更贴近", accent: "你的生意。",
    body: "查询门店已同步的信息，并与我们的团队保持联系。",
    features: ["集中查询你的门店", "单据与库存", "跟进咨询与支持请求"],
    permissions: "可用功能取决于你的账号权限。",
    welcome: "欢迎回来", signIn: "登录你的账号", help: "请使用为你的门店提供的账号和密码。",
    username: "门店用户名", usernamePlaceholder: "你的用户名", password: "密码", passwordPlaceholder: "你的密码",
    enter: "登录客户门户", entering: "正在登录…", accountHelp: "如果尚未获得访问权限，请联系 esPOS 团队。"
  }
} as const;

const featureIcons = [Storefront, Package, ChatsCircle];

export function TenantLoginScreen({ username, password, busy, notice, onUsernameChange, onPasswordChange, onSubmit }: {
  username: string; password: string; busy: boolean; notice: Notice;
  onUsernameChange: (value: string) => void; onPasswordChange: (value: string) => void;
  onSubmit: FormEventHandler<HTMLFormElement>;
}) {
  const { language } = useI18n();
  const copy = content[language];
  return <PortalLoginLayout portal={copy.portal} kicker={copy.kicker} title={copy.title} accent={copy.accent}
    description={copy.body} features={copy.features.map((title, index) => ({ title, icon: featureIcons[index] }))}
    note={copy.permissions} headingId="tenant-signin-title">
        <p className="tenant-login-eyebrow">{copy.welcome}</p>
        <h2 id="tenant-signin-title">{copy.signIn}</h2>
        <p id="tenant-signin-help" className="tenant-login-help">{copy.help}</p>
        {notice && <p className={`notice ${notice.type}`} role={notice.type === "error" ? "alert" : "status"}>{notice.text}</p>}
        <form onSubmit={onSubmit} aria-describedby="tenant-signin-help" aria-busy={busy}>
          <label>{copy.username}<input name="username" autoComplete="username" autoCapitalize="none" spellCheck={false} placeholder={copy.usernamePlaceholder} value={username} onChange={event => onUsernameChange(event.target.value)} required disabled={busy} /></label>
          <label>{copy.password}<input name="password" type="password" autoComplete="current-password" placeholder={copy.passwordPlaceholder} value={password} onChange={event => onPasswordChange(event.target.value)} required disabled={busy} /></label>
          <button className="tenant-login-submit" type="submit" disabled={busy}><span>{busy ? copy.entering : copy.enter}</span><ArrowRight size={18} aria-hidden="true" /></button>
        </form>
        <p className="tenant-login-account-help">{copy.accountHelp}</p>
  </PortalLoginLayout>;
}
