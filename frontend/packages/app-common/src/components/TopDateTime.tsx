import { useEffect, useState } from "react";
import type { LocaleCode } from "../types";

type TopDateTimeProps = {
  locale: LocaleCode;
};

function formatTopDateTime(date: Date, locale: LocaleCode) {
  const formatter = new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : "es-ES", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
  const parts = Object.fromEntries(formatter.formatToParts(date).map((part) => [part.type, part.value]));
  return `${parts.day}/${parts.month}/${parts.year} ${parts.hour}:${parts.minute}:${parts.second}`;
}

export function TopDateTime({ locale }: TopDateTimeProps) {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const interval = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(interval);
  }, []);

  return (
    <time className="top-date-time" dateTime={now.toISOString()}>
      {formatTopDateTime(now, locale)}
    </time>
  );
}
