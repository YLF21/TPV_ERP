import type { HardwareBridge, HardwareConfig, CustomerDisplayScreen } from "../hardware/hardware";
import { createCustomerDisplayIdleState, createCustomerDisplayPaymentState, createCustomerDisplaySaleState } from "../hardware/hardware";
import { ErpSelect } from "./ErpSelect";
import { SerialPortSelect } from "./SerialPortSelect";
import { SerialBaudRateSelect } from "./SerialBaudRateSelect";
import "./CustomerDisplaySettings.css";

type Props = {
  t: (key: string) => string;
  config: HardwareConfig;
  updateConfig: (patch: Partial<HardwareConfig>) => void;
  customerDisplays: CustomerDisplayScreen[];
  listSerialPorts?: HardwareBridge["listSerialPorts"];
  desktopHardwareAvailable: boolean;
  refreshCustomerDisplays: () => void | Promise<void>;
  openCustomerDisplay: () => void | Promise<void>;
  closeCustomerDisplay: () => void | Promise<void>;
  updateCustomerDisplay: (state: ReturnType<typeof createCustomerDisplayIdleState>) => void | Promise<void>;
};


export function CustomerDisplaySettings({
  t, config, updateConfig, customerDisplays, listSerialPorts, desktopHardwareAvailable,
  refreshCustomerDisplays, openCustomerDisplay, closeCustomerDisplay, updateCustomerDisplay,
}: Props) {
  const isDigital = config.customerDisplayMode === "DIGITAL_SCREEN";

  return <section className="hardware-section hardware-section-wide customer-display-settings">
    <h2>{t("hardware.customerDisplay")}</h2>

    <label className="hardware-check customer-display-enabled">
      <input type="checkbox" checked={config.customerDisplayEnabled}
        onChange={(event) => updateConfig({ customerDisplayEnabled: event.target.checked })} />
      <span>{t("hardware.customerDisplayEnabled")}</span>
    </label>

    <fieldset className="customer-display-mode">
      <legend>{t("hardware.customerDisplayMode")}</legend>
      <div className="customer-display-mode-options">
        <label className="customer-display-mode-option">
          <input type="radio" name="customer-display-mode" value="LINE_DISPLAY"
            checked={!isDigital} onChange={() => updateConfig({ customerDisplayMode: "LINE_DISPLAY" })} />
          <span>{t("hardware.customerDisplayLineDisplay")}</span>
        </label>
        <label className="customer-display-mode-option">
          <input type="radio" name="customer-display-mode" value="DIGITAL_SCREEN"
            checked={isDigital} onChange={() => updateConfig({ customerDisplayMode: "DIGITAL_SCREEN" })} />
          <span>{t("hardware.customerDisplayDigitalScreen")}</span>
        </label>
      </div>
    </fieldset>

    <section className="customer-display-group" aria-labelledby="customer-display-connection-heading">
      <h3 id="customer-display-connection-heading">{t("hardware.customerDisplayConnectionSettings")}</h3>
      <div className="customer-display-fields">
        {isDigital ? <label><span>{t("hardware.customerDisplayScreen")}</span>
          <ErpSelect aria-label={t("hardware.customerDisplayScreen")} value={config.customerDisplayScreenId}
            onChange={(value) => updateConfig({ customerDisplayScreenId: value })}
            options={[
              { value: "", label: t("hardware.customerDisplayChooseScreen") },
              ...customerDisplays.filter((display) => !display.primary && !display.operator).map((display) => ({
                value: display.id, label: display.label,
              })),
            ]} />
        </label> : <>
          <label className="customer-display-connection-choice"><span>{t("hardware.customerDisplayConnection")}</span>
            <ErpSelect aria-label={t("hardware.customerDisplayConnection")} value={config.customerDisplayConnection}
              onChange={(value) => updateConfig({ customerDisplayConnection: value as HardwareConfig["customerDisplayConnection"] })}
              options={[
                { value: "SERIAL", label: t("hardware.customerDisplaySerial") },
                { value: "NETWORK", label: t("hardware.customerDisplayNetwork") },
              ]} />
          </label>
          {config.customerDisplayConnection === "SERIAL" ? <>
            <SerialPortSelect t={t} label={t("hardware.customerDisplayDevicePath")} value={config.customerDisplayDevicePath}
              onChange={(value) => updateConfig({ customerDisplayDevicePath: value })}
              listSerialPorts={listSerialPorts} available={desktopHardwareAvailable} />
            <label><span>{t("hardware.customerDisplayBaudRate")}</span>
              <SerialBaudRateSelect label={t("hardware.customerDisplayBaudRate")} value={config.customerDisplaySerialBaudRate}
                onChange={(value) => updateConfig({ customerDisplaySerialBaudRate: value })} />
            </label>
          </> : <>
            <label><span>{t("hardware.customerDisplayHost")}</span>
              <input value={config.customerDisplayHost}
                onChange={(event) => updateConfig({ customerDisplayHost: event.target.value })} />
            </label>
            <label><span>{t("hardware.customerDisplayPort")}</span>
              <input type="number" min={1} max={65535} value={config.customerDisplayPort}
                onChange={(event) => updateConfig({ customerDisplayPort: Number(event.target.value) })} />
            </label>
          </>}
          <div className="customer-display-options-grid">
            <label><span>{t("hardware.customerDisplayProtocol")}</span>
              <ErpSelect aria-label={t("hardware.customerDisplayProtocol")} value={config.customerDisplayProtocol}
                onChange={(value) => updateConfig({ customerDisplayProtocol: value as HardwareConfig["customerDisplayProtocol"] })}
                options={[
                  { value: "ESC_POS_DISPLAY", label: t("hardware.customerDisplayEscPos") },
                  { value: "PLAIN_TEXT", label: t("hardware.customerDisplayPlainText") },
                ]} />
            </label>
            <label><span>{t("hardware.customerDisplayColumns")}</span>
              <input type="number" min={10} max={80} value={config.customerDisplayColumns}
                onChange={(event) => updateConfig({ customerDisplayColumns: Number(event.target.value) })} />
            </label>
            <label><span>{t("hardware.customerDisplayEncoding")}</span>
              <ErpSelect aria-label={t("hardware.customerDisplayEncoding")} value={config.customerDisplayEncoding}
                onChange={(value) => updateConfig({ customerDisplayEncoding: value as HardwareConfig["customerDisplayEncoding"] })}
                options={[
                  { value: "CP858", label: t("hardware.customerDisplayEncodingCp858") },
                  { value: "ASCII", label: t("hardware.customerDisplayEncodingAscii") },
                ]} />
            </label>
          </div>
        </>}
      </div>
      {!isDigital && <>
        <p className="hardware-help">{t("hardware.customerDisplayCompatibility")}</p>
        <p className="hardware-help">{t("hardware.customerDisplayEncodingHelp")}</p>
      </>}
    </section>

    <section className="customer-display-group" aria-labelledby="customer-display-idle-heading">
      <h3 id="customer-display-idle-heading">{t("hardware.customerDisplayIdleSettings")}</h3>
      <div className="customer-display-fields">
        <label><span>{t("hardware.customerDisplayIdleLine1")}</span>
          <input value={config.customerDisplayIdleLine1}
            onChange={(event) => updateConfig({ customerDisplayIdleLine1: event.target.value })} />
        </label>
        <label><span>{t("hardware.customerDisplayIdleLine2")}</span>
          <input value={config.customerDisplayIdleLine2}
            onChange={(event) => updateConfig({ customerDisplayIdleLine2: event.target.value })} />
        </label>
      </div>
    </section>

    <section className="customer-display-group" aria-labelledby="customer-display-test-heading">
      <h3 id="customer-display-test-heading">{t("hardware.customerDisplayTestSettings")}</h3>
      <div className="hardware-inline-actions customer-display-actions">
        {isDigital && <button type="button" onClick={refreshCustomerDisplays} disabled={!desktopHardwareAvailable}>{t("hardware.detectScreens")}</button>}
        <button type="button" onClick={openCustomerDisplay} disabled={!desktopHardwareAvailable}>{t("hardware.openCustomerDisplay")}</button>
        <button type="button" onClick={closeCustomerDisplay} disabled={!desktopHardwareAvailable}>{t("hardware.closeCustomerDisplay")}</button>
      </div>
      <div className="hardware-inline-actions customer-display-actions">
        <button type="button" disabled={!desktopHardwareAvailable} onClick={() => updateCustomerDisplay(
          createCustomerDisplayIdleState(config.customerDisplayIdleLine1, config.customerDisplayIdleLine2, true))}>
          {t("hardware.sendIdleDisplay")}
        </button>
        <button type="button" disabled={!desktopHardwareAvailable} onClick={() => updateCustomerDisplay(
          createCustomerDisplaySaleState({ name: "TEST HARDWARE", quantity: 1, price: 1 }, true))}>
          {t("hardware.sendSaleDisplay")}
        </button>
        <button type="button" disabled={!desktopHardwareAvailable} onClick={() => updateCustomerDisplay(
          createCustomerDisplayPaymentState({ total: 12.5, change: 2.5 }, true))}>
          {t("hardware.sendPaymentDisplay")}
        </button>
      </div>
    </section>
  </section>;
}
