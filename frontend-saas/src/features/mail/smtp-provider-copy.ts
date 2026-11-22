import { useI18n } from "../../i18n";

const entries = {
  otherServer: ["Otro servidor (configuración manual)", "Other server (manual setup)", "其他服务器（手动配置）"],
  openPasswordProvider: ["Abrir {provider}", "Open {provider}", "打开 {provider}"],
  passwordBrowserHint: ["Se abre en tu navegador. Inicia sesión en la cuenta de correo que quieres conectar.", "Opens in your browser. Sign in to the email account you want to connect.", "将在浏览器中打开。请登录要连接的邮箱账户。"],
  passwordStepsTitle: ["Cómo obtener la contraseña · paso a paso", "How to get the password · step by step", "如何获取密码 · 操作步骤"],
  passwordStepsFinish: ["Pega la contraseña o el código en Contraseña SMTP y pulsa Conectar correo.", "Paste the password or code into SMTP password and click Connect email.", "将密码或授权码粘贴到 SMTP 密码字段，点击连接邮箱。"],
  googlePasswordUnavailable: ["Si no aparece la opción, comprueba la verificación en dos pasos. Algunas cuentas de empresa o con Protección Avanzada no permiten estas contraseñas.", "If the option is missing, check two-step verification. Some organization or Advanced Protection accounts do not allow app passwords.", "若找不到此选项，请检查两步验证设置。部分组织账户或启用高级保护的账户不支持应用专用密码。"],
  passwordOptionsUnavailable: ["Si no encuentras la opción, consulta la ayuda del proveedor o al administrador de tu correo. Los menús pueden variar según la cuenta.", "If the option is missing, check your provider's help or contact your email administrator. Menus may vary by account.", "若找不到此选项，请查看服务商帮助或联系邮箱管理员。不同账户的菜单可能有所不同。"],
  "passwordSteps.gmail.1": ["Pulsa Abrir Google e inicia sesión con el mismo Gmail que has indicado arriba.", "Click Open Google and sign in with the Gmail address entered above.", "点击打开 Google，并登录上方填写的 Gmail 账户。"],
  "passwordSteps.gmail.2": ["Activa la verificación en dos pasos si Google te lo pide y vuelve a Contraseñas de aplicaciones.", "Enable two-step verification if Google asks, then return to App passwords.", "如 Google 提示，请启用两步验证，然后返回应用专用密码页面。"],
  "passwordSteps.gmail.3": ["Escribe esPOS como nombre de la aplicación, crea la contraseña y copia los 16 caracteres generados.", "Enter esPOS as the app name, create the password and copy the 16 generated characters.", "将应用名称填写为 esPOS，创建密码并复制生成的 16 位密码。"],
  "passwordSteps.yahoo.1": ["Pulsa Abrir Yahoo e inicia sesión. Entrarás en Seguridad de la cuenta.", "Click Open Yahoo and sign in to Account Security.", "点击打开 Yahoo，登录并进入账户安全页面。"],
  "passwordSteps.yahoo.2": ["En Conexiones externas, elige Crear contraseña de aplicación.", "Under External connections, select Create app password.", "在外部连接中，选择创建应用密码。"],
  "passwordSteps.yahoo.3": ["Indica esPOS como nombre y pulsa Generar contraseña. Copia la contraseña que aparece.", "Enter esPOS as the name and click Generate password. Copy the password shown.", "输入名称 esPOS 并点击生成密码，然后复制显示的密码。"],
  "passwordSteps.qq.1": ["Pulsa Abrir QQ e inicia sesión en la versión web de tu correo.", "Click Open QQ and sign in to your webmail.", "点击打开 QQ 并登录网页版邮箱。"],
  "passwordSteps.qq.2": ["En Ajustes → Cuenta o Cuenta y seguridad, busca los servicios POP3/IMAP/SMTP y activa IMAP/SMTP.", "Under Settings → Account or Account and security, find POP3/IMAP/SMTP services and enable IMAP/SMTP.", "进入设置 → 账户或账号与安全，找到 POP3/IMAP/SMTP 服务并开启 IMAP/SMTP。"],
  "passwordSteps.qq.3": ["Completa la verificación indicada y genera un código de autorización. Copia ese código, no tu contraseña de QQ.", "Complete the requested verification and generate an authorization code. Copy that code, not your QQ password.", "按提示完成验证并生成授权码。请复制授权码，而不是 QQ 登录密码。"],
  "passwordSteps.icloud.1": ["Pulsa Abrir Apple e inicia sesión con tu cuenta de iCloud. Debe tener autenticación de doble factor activada.", "Click Open Apple and sign in with your iCloud account. Two-factor authentication must be enabled.", "点击打开 Apple 并登录 iCloud 账户。账户须已启用双重认证。"],
  "passwordSteps.icloud.2": ["En Inicio de sesión y seguridad, entra en Contraseñas específicas de apps.", "Under Sign-In and Security, open App-Specific Passwords.", "在登录与安全中，打开 App 专用密码。"],
  "passwordSteps.icloud.3": ["Genera una contraseña para esPOS y cópiala.", "Generate a password for esPOS and copy it.", "为 esPOS 生成一个密码并复制。"],
  "passwordSteps.netease163.1": ["Pulsa Abrir 163 e inicia sesión en tu correo de NetEase.", "Click Open 163 and sign in to your NetEase mailbox.", "点击打开 163 并登录网易邮箱。"],
  "passwordSteps.netease163.2": ["En Ajustes → POP3/SMTP/IMAP, activa IMAP/SMTP o POP3/SMTP.", "Under Settings → POP3/SMTP/IMAP, enable IMAP/SMTP or POP3/SMTP.", "进入设置 → POP3/SMTP/IMAP，开启 IMAP/SMTP 或 POP3/SMTP 服务。"],
  "passwordSteps.netease163.3": ["Sigue la verificación de seguridad para obtener el código de autorización y cópialo.", "Follow the security verification to obtain an authorization code and copy it.", "按提示完成安全验证，获取并复制授权码。"],
  "passwordSteps.exmail.1": ["Pulsa Abrir Exmail e inicia sesión en tu correo de empresa.", "Click Open Exmail and sign in to your business mailbox.", "点击打开 Exmail 并登录企业邮箱。"],
  "passwordSteps.exmail.2": ["En Ajustes → Configuración de envío y recepción, activa IMAP/SMTP o POP/SMTP.", "Under Settings → Sending and receiving settings, enable IMAP/SMTP or POP/SMTP.", "进入设置 → 收发信设置，开启 IMAP/SMTP 或 POP/SMTP 服务。"],
  "passwordSteps.exmail.3": ["En Vinculación del correo, genera una contraseña nueva para el cliente y cópiala. Si está bloqueado, consulta al administrador.", "Under Mailbox binding, generate a new client password and copy it. If blocked, contact your administrator.", "在邮箱绑定中生成新密码并复制。如无权限，请联系管理员。"],
  "passwordSteps.custom.1": ["Abre la web oficial de tu proveedor e inicia sesión en tu correo.", "Open your provider's official website and sign in to your mailbox.", "打开邮箱服务商的官方网站并登录邮箱。"],
  "passwordSteps.custom.2": ["Busca Seguridad, Contraseñas de aplicación o Acceso SMTP. Activa el acceso para aplicaciones si es necesario.", "Find Security, App passwords or SMTP access. Enable access for apps if required.", "查找安全、应用专用密码或 SMTP 访问设置，按需开启客户端访问。"],
  "passwordSteps.custom.3": ["Genera la contraseña o el código que indique el proveedor. Si no ofrece esta opción, consulta qué credenciales SMTP debes utilizar.", "Generate the password or code specified by the provider. If unavailable, ask which SMTP credentials to use.", "按服务商要求生成密码或授权码。如无此选项，请咨询应使用的 SMTP 凭据。"],
  appPasswordHint: ["Genera una contraseña de aplicación en la seguridad de tu cuenta de correo e introdúcela aquí. No uses tu contraseña habitual.", "Generate an app password in your email account security settings and enter it here. Do not use your regular password.", "请在邮箱账户的安全设置中生成应用专用密码，并在此输入。请勿使用普通登录密码。"],
  authorizationCodeHint: ["Activa el servicio SMTP en los ajustes de tu correo y genera un código de autorización. Introduce ese código en el campo de contraseña.", "Enable SMTP in your email settings and generate an authorization code. Enter that code in the password field.", "请在邮箱设置中开启 SMTP 服务并生成授权码，然后在密码栏输入该授权码。"],
  gmailPasswordHint: ["Gmail necesita una contraseña de aplicación de Google. No uses la contraseña habitual de tu cuenta.", "Gmail requires a Google app password. Do not use your regular account password.", "Gmail 需要 Google 应用专用密码，请勿使用账户的普通密码。"],
  smtpHint: ["Usa los datos de tu proveedor. Si exige una contraseña de aplicación, introdúcela aquí.", "Use your provider's settings. If an app password is required, enter it here.", "请填写服务商提供的设置。如果需要应用专用密码，请在此输入。"],
  provider: ["Proveedor SMTP", "SMTP provider", "SMTP 服务商"],
} as const;

export function useSmtpProviderCopy() {
  const { language } = useI18n();
  const index = language === "es" ? 0 : language === "en" ? 1 : 2;
  return (key: keyof typeof entries) => entries[key][index];
}
