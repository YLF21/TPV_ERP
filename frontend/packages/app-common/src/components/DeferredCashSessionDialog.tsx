import { lazy, Suspense, type ComponentProps } from "react";

const Dialog = lazy(() => import("./SaleCashSessionDialog")
  .then(module => ({ default: module.SaleCashSessionDialog })));

export function DeferredCashSessionDialog(props: ComponentProps<typeof Dialog>) {
  return <Suspense fallback={null}><Dialog {...props} /></Suspense>;
}
