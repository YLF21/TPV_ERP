import { useCallback, useEffect, useRef } from "react";

export type TableArrowDirection = "ArrowUp" | "ArrowDown";
type ArrowKeyEvent = Pick<KeyboardEvent, "key" | "repeat">
  & Partial<Pick<KeyboardEvent, "ctrlKey" | "altKey" | "metaKey" | "shiftKey" | "isComposing">>;

const INITIAL_DELAY_MS = 180;
const REPEAT_DELAY_MS = 70;

export function useTableArrowRepeat(
  onStep: (direction: TableArrowDirection) => boolean | void,
  enabled = true,
) {
  const onStepRef = useRef(onStep);
  const enabledRef = useRef(enabled);
  const directionRef = useRef<TableArrowDirection | null>(null);
  const timeoutRef = useRef<number | null>(null);
  const generationRef = useRef(0);

  onStepRef.current = onStep;
  enabledRef.current = enabled;

  const stop = useCallback(() => {
    generationRef.current += 1;
    directionRef.current = null;
    if (timeoutRef.current !== null) {
      window.clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
  }, []);

  const handleKeyDown = useCallback((event: ArrowKeyEvent) => {
    if (!enabledRef.current || document.visibilityState === "hidden"
      || event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || event.isComposing) {
      stop();
      return;
    }
    if (event.key !== "ArrowUp" && event.key !== "ArrowDown") {
      stop();
      return;
    }
    if (event.repeat) return;

    stop();
    const direction = event.key;
    directionRef.current = direction;
    const generation = generationRef.current;

    const schedule = (delay: number) => {
      timeoutRef.current = window.setTimeout(() => {
        timeoutRef.current = null;
        if (generationRef.current !== generation || directionRef.current !== direction) return;
        if (!enabledRef.current || document.visibilityState === "hidden") {
          stop();
          return;
        }
        if (onStepRef.current(direction) === false) {
          stop();
          return;
        }
        if (generationRef.current === generation && directionRef.current === direction && enabledRef.current) {
          schedule(REPEAT_DELAY_MS);
        }
      }, delay);
    };

    if (onStepRef.current(direction) === false) {
      stop();
      return;
    }
    if (generationRef.current === generation && directionRef.current === direction && enabledRef.current) {
      schedule(INITIAL_DELAY_MS);
    }
  }, [stop]);

  useEffect(() => {
    if (!enabled) stop();
  }, [enabled, stop]);

  useEffect(() => {
    const handleGlobalKeyDown = (event: KeyboardEvent) => {
      if (directionRef.current === null) return;
      if (event.isComposing || event.ctrlKey || event.altKey || event.metaKey || event.shiftKey
        || event.key !== directionRef.current) stop();
    };
    const handleKeyUp = (event: KeyboardEvent) => {
      if (event.key === directionRef.current) stop();
    };
    const handleVisibilityChange = () => {
      if (document.visibilityState === "hidden") stop();
    };

    window.addEventListener("keydown", handleGlobalKeyDown, true);
    window.addEventListener("keyup", handleKeyUp, true);
    window.addEventListener("blur", stop);
    window.addEventListener("pointerdown", stop, true);
    window.addEventListener("compositionstart", stop, true);
    document.addEventListener("visibilitychange", handleVisibilityChange);
    return () => {
      window.removeEventListener("keydown", handleGlobalKeyDown, true);
      window.removeEventListener("keyup", handleKeyUp, true);
      window.removeEventListener("blur", stop);
      window.removeEventListener("pointerdown", stop, true);
      window.removeEventListener("compositionstart", stop, true);
      document.removeEventListener("visibilitychange", handleVisibilityChange);
      stop();
    };
  }, [stop]);

  return handleKeyDown;
}
