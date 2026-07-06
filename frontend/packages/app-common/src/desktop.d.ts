import type { HardwareBridge } from "./hardware/hardware";

declare global {
  interface Window {
    tpvDesktop?: {
      closeApplication: () => Promise<void>;
      hardware?: HardwareBridge;
    };
  }
}
