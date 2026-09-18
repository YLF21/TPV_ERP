import type { LocaleCode } from "../types";

const values: Record<LocaleCode, Record<string, string>> = {
  es: {
    "sale.controlDelivery.pending": "Eventos de control pendientes:",
    "sale.controlDelivery.sending": "Enviando eventos de control…",
    "sale.controlDelivery.retry": "Reintentar envío",
    "sale.controlDelivery.context": "No se pudo verificar el registro de control. Reintenta antes de eliminar productos.",
    "sale.controlDelivery.storage": "No se pudo acceder al registro local de control. Reintenta o revisa el almacenamiento.",
    "sale.controlDelivery.saveFailed": "No se pudo guardar el registro de control. Los productos permanecen en el carrito. Reintenta.",
    "sale.controlDelivery.network": "Sin conexión con control. Los eventos guardados se enviarán al recuperar la conexión.",
    "sale.controlDelivery.rejected": "El servidor rechazó un evento de control. Está conservado para revisión; no se ha descartado.",
    "sale.controlDelivery.session": "Hay eventos conservados. Vuelve a iniciar sesión con el mismo usuario para enviarlos.",
    "sale.controlDelivery.saving": "Guardando registro de control…",
  },
  en: {
    "sale.controlDelivery.pending": "Pending control events:",
    "sale.controlDelivery.sending": "Sending control events…",
    "sale.controlDelivery.retry": "Retry delivery",
    "sale.controlDelivery.context": "The control context could not be verified. Retry before removing products.",
    "sale.controlDelivery.storage": "The local control records could not be accessed. Retry or check the storage.",
    "sale.controlDelivery.saveFailed": "The control record could not be saved. Products remain in the cart. Retry.",
    "sale.controlDelivery.network": "Control is offline. Saved events will be sent when the connection returns.",
    "sale.controlDelivery.rejected": "The server rejected a control event. It is retained for review and has not been discarded.",
    "sale.controlDelivery.session": "Events are retained. Sign in again as the same user to send them.",
    "sale.controlDelivery.saving": "Saving control record…",
  },
  zh: {
    "sale.controlDelivery.pending": "待发送的监控事件：",
    "sale.controlDelivery.sending": "正在发送监控事件…",
    "sale.controlDelivery.retry": "重试发送",
    "sale.controlDelivery.context": "无法验证监控记录身份。请重试后再删除商品。",
    "sale.controlDelivery.storage": "无法访问本地监控记录。请重试或检查存储设备。",
    "sale.controlDelivery.saveFailed": "无法保存监控记录。商品仍保留在购物车中，请重试。",
    "sale.controlDelivery.network": "监控连接已断开。恢复连接后将发送已保存的事件。",
    "sale.controlDelivery.rejected": "服务器拒绝了一个监控事件。该事件已保留待核查，未被丢弃。",
    "sale.controlDelivery.session": "事件已保留。请使用原用户重新登录后发送。",
    "sale.controlDelivery.saving": "正在保存监控记录…",
  },
};
export const saleControlDeliveryMessages = (locale: LocaleCode) => values[locale];
