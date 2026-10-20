import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowClockwise } from "@phosphor-icons/react";
import type { HardwareBridge, HardwareSerialPort } from "../hardware/hardware";
import { ErpSelect } from "./ErpSelect";
import "./SerialPortSelect.css";

export function SerialPortSelect({ t, label, value, onChange, listSerialPorts, available }: {
  t: (key: string) => string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  listSerialPorts?: HardwareBridge["listSerialPorts"];
  available: boolean;
}) {
  const [ports, setPorts] = useState<HardwareSerialPort[]>([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const mounted = useRef(false);
  const pending = useRef(false);
  const refresh = useCallback(async () => {
    if (!available || !listSerialPorts || pending.current) return;
    pending.current = true;
    setLoading(true);
    setFailed(false);
    try {
      const result = await listSerialPorts();
      if (!mounted.current) return;
      if (result.ok) {
        setPorts(result.ports);
        setLoaded(true);
      } else setFailed(true);
    } catch {
      if (mounted.current) setFailed(true);
    } finally {
      pending.current = false;
      if (mounted.current) setLoading(false);
    }
  }, [available, listSerialPorts]);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    return () => { mounted.current = false; };
  }, [refresh]);

  const selectedPath = value.trim().toUpperCase();
  const detected = ports.some(port => port.path === selectedPath);
  const options = [
    { value: "", label: t("hardware.chooseSerialPort") },
    ...(value && !detected ? [{ value, label: loaded
      ? `${selectedPath} (${t("hardware.serialPortNotDetected")})` : selectedPath }] : []),
    ...ports.map(port => ({ value: port.path === selectedPath ? value : port.path, label: port.path })),
  ];

  return <div className="hardware-serial-port-field">
    <div className="hardware-serial-port-picker">
      <label><span>{label}</span>
        <ErpSelect aria-label={label} value={value}
          onChange={onChange} options={options} />
      </label>
      <button type="button" className="sale-settings-action-button" onClick={refresh} disabled={!available || !listSerialPorts || loading}
        aria-busy={loading} aria-label={t("hardware.refreshSerialPorts")} title={t("hardware.refreshSerialPorts")}>
        <span className="hardware-serial-port-refresh-text">{t("hardware.refreshSerialPorts")}</span>
        <ArrowClockwise className="hardware-serial-port-refresh-icon" size={18} aria-hidden="true" />
      </button>
    </div>
    {(failed || !listSerialPorts) && <p className="hardware-help" role="status">{t("hardware.serialPortsFailed")}</p>}
    {loaded && !loading && !failed && ports.length === 0
      && <p className="hardware-help" role="status">{t("hardware.noSerialPorts")}</p>}
  </div>;
}
