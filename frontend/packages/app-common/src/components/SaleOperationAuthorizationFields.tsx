import type { SaleOperationAuthorization } from "../sale/operationSecurity";
import type { LocaleCode } from "../types";

type Props = {
  locale: LocaleCode;
  authorization: SaleOperationAuthorization;
  username: string;
  password: string;
  disabled?: boolean;
  autoFocus?: boolean;
  onUsernameChange: (value: string) => void;
  onPasswordChange: (value: string) => void;
};

const copy = {
  es: {
    currentHint: "Confirma la operación con tu contraseña.",
    delegatedHint: "Introduce el usuario y la contraseña de una persona con permiso.",
    username: "Usuario autorizador",
    currentPassword: "Tu contraseña",
    delegatedPassword: "Contraseña del autorizador",
  },
  en: {
    currentHint: "Confirm the operation with your password.",
    delegatedHint: "Enter the username and password of a user with permission.",
    username: "Authorizing user",
    currentPassword: "Your password",
    delegatedPassword: "Authorizer password",
  },
  zh: {
    currentHint: "请输入你的密码以确认此操作。",
    delegatedHint: "请输入具有所需权限的用户的用户名和密码。",
    username: "授权用户",
    currentPassword: "你的密码",
    delegatedPassword: "授权用户密码",
  },
} as const;

export function SaleOperationAuthorizationFields({
  locale,
  authorization,
  username,
  password,
  disabled = false,
  autoFocus = false,
  onUsernameChange,
  onPasswordChange,
}: Props) {
  if (authorization.mode === "DIRECT") return null;
  const t = copy[locale];
  const delegated = authorization.mode === "DELEGATED";

  return (
    <div className="sale-operation-authorization-fields">
      <p>{delegated ? t.delegatedHint : t.currentHint}</p>
      {delegated && (
        <label>
          <span>{t.username}</span>
          <input
            autoFocus={autoFocus}
            autoComplete="username"
            maxLength={128}
            value={username}
            disabled={disabled}
            onChange={(event) => onUsernameChange(event.currentTarget.value)}
          />
        </label>
      )}
      <label>
        <span>{delegated ? t.delegatedPassword : t.currentPassword}</span>
        <input
          autoFocus={autoFocus && !delegated}
          type="password"
          autoComplete="current-password"
          maxLength={128}
          value={password}
          disabled={disabled}
          onChange={(event) => onPasswordChange(event.currentTarget.value)}
        />
      </label>
    </div>
  );
}
