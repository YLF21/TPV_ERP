import type { MarketingLanguage } from "./marketingI18n";

// Loaded with the product-tour dialog, not with the initial page content.
type TourExampleStep = {
  actions: readonly string[];
  example: string;
  benefit: string;
};

type TourExamples = {
  stepBodies: readonly (readonly string[])[];
  stepDetails: readonly (readonly TourExampleStep[])[];
};

const tourExamples = {
  es: {
    stepBodies: [
      ["Prepara el ticket, comprueba el total y diferencia lo cobrado del saldo pendiente autorizado.", "Consulta stock y deudas del cliente sin abandonar el trabajo con teclado.", "Distingue la venta registrada en tienda de los envíos pendientes a servicios externos."],
      ["Relaciona existencias, movimientos e informes antes de decidir una compra o transferencia.", "Asigna columnas y revisa la vista previa antes de aplicar una importación de productos.", "Configura qué tareas puede realizar cada cuenta, sin dar acceso administrativo a todos."],
      ["Cuenta las unidades físicas y revisa su diferencia respecto al stock registrado.", "Contrasta mercancía y documentos ya confirmados sin duplicar la entrada de stock.", "Traslada unidades desde reserva a tienda con una transferencia autorizada."],
      ["Filtra documentos recibidos por tienda, fecha, tipo y estado.", "Consulta stock y contadores de sincronización sin dar por actualizada una tienda desconectada.", "Revisa licencias, facturas del servicio y conversaciones de soporte según permisos."]
    ],
    stepDetails: [
      [
        {
          actions: [
            "Añade los artículos y revisa cantidades y total del ticket.",
            "Elige efectivo entre los medios habilitados y comprueba el cambio.",
            "Gestiona el saldo pendiente de un cliente solo con las autorizaciones requeridas."
          ],
          example: "Ejemplo orientativo: total 2,50; efectivo indicado 1,00; faltan 1,50 antes de finalizar. No es deuda automática: dejarlo pendiente exige cliente identificado y autorización.",
          benefit: "Distingue el dinero cobrado del saldo que queda pendiente de un cliente."
        },
        {
          actions: [
            "Selecciona el artículo sobre el que pregunta el cliente.",
            "Pulsa F5 para consultar el stock de ese artículo.",
            "Con el cliente seleccionado, abre sus deudas con Ctrl+D."
          ],
          example: "Ejemplo orientativo: un cliente pregunta por otra unidad y una deuda anterior. Consulta el artículo con F5 y su cuenta con Ctrl+D, según permisos.",
          benefit: "Resuelve ambas consultas conservando el contexto de la venta."
        },
        {
          actions: [
            "Prepara el ticket con acceso al servicio de tienda.",
            "Abre Estado operativo para revisar los estados disponibles.",
            "Distingue envíos pendientes, enviados y errores de sincronización."
          ],
          example: "Ejemplo orientativo: al cierre, una venta está registrada y su envío sigue pendiente. Revisa la cola; el servicio local es necesario y los envíos externos requieren internet.",
          benefit: "No confunde una venta registrada con su entrega a servicios externos."
        }
      ],
      [
        {
          actions: [
            "Selecciona el artículo y consulta sus existencias por almacén.",
            "Revisa sus movimientos para explicar entradas y salidas.",
            "Contrasta la actividad de ventas antes de decidir la reposición."
          ],
          example: "Ejemplo orientativo: queda poco de una referencia en tienda. Consulta si hay reserva y revisa movimientos y ventas antes de decidir una compra o transferencia.",
          benefit: "Distingue una necesidad de compra de una reposición entre almacenes."
        },
        {
          actions: [
            "Carga la hoja y asigna las columnas de código, nombre y precio.",
            "Revisa la vista previa, coincidencias y filas con errores.",
            "Comprueba los datos aceptados antes de aplicar los cambios."
          ],
          example: "Ejemplo orientativo: una hoja trae nuevas referencias y cambios de precio. Revisa el mapeo y las filas aceptadas antes de aplicarlos al catálogo.",
          benefit: "Detecta datos que necesitan revisión antes de modificar productos."
        },
        {
          actions: [
            "Identifica las tareas de cada usuario antes de asignar su rol.",
            "Configura los permisos de las funciones que necesita.",
            "Revisa las operaciones permitidas antes de guardar el acceso."
          ],
          example: "Ejemplo orientativo de configuración: caja y administración necesitan tareas distintas. Revisa quién puede mantener el catálogo y quién solo usa las funciones asignadas.",
          benefit: "Ajusta el acceso de cada cuenta a sus tareas y responsabilidades."
        }
      ],
      [
        {
          actions: [
            "Selecciona el almacén e inicia o recupera su recuento.",
            "Escanea el artículo y guarda la cantidad realmente contada.",
            "Revisa cantidad prevista, contada y diferencia antes de confirmar."
          ],
          example: "Ejemplo orientativo: el sistema registra 10 unidades y cuentas 8. Guarda el recuento y revisa la diferencia de 2 unidades antes de confirmar.",
          benefit: "La actualización parte de un recuento revisado, no de una cifra supuesta."
        },
        {
          actions: [
            "Abre un albarán o factura de entrada ya confirmado.",
            "Escanea la mercancía y revisa faltantes o sobrantes.",
            "Confirma expresamente las diferencias si cierras la comprobación."
          ],
          example: "Ejemplo orientativo: el documento confirmado indica 12 unidades y encuentras 11. Revisa la unidad faltante; la comprobación no registra otra entrada de stock.",
          benefit: "Detecta la discrepancia sin duplicar el movimiento de la recepción."
        },
        {
          actions: [
            "Consulta las existencias del artículo y el stock de reserva.",
            "Elige almacenes de origen y destino y la cantidad a trasladar.",
            "Con el permiso requerido, registra la transferencia y revisa el resultado."
          ],
          example: "Ejemplo orientativo: tienda necesita 2 unidades disponibles en reserva. Transfiérelas entre los almacenes elegidos y revisa sus cantidades resultantes.",
          benefit: "Deja registrado de dónde salen y a dónde llegan las unidades."
        }
      ],
      [
        {
          actions: [
            "Selecciona las tiendas que tu cuenta tiene autorizadas.",
            "Filtra documentos por fechas, tipo, estado o número.",
            "Comprueba tienda, estado y total de cada resultado."
          ],
          example: "Ejemplo orientativo: dirección busca las facturas de dos tiendas de un mismo periodo. Cada fila conserva la tienda y el estado del documento recibido.",
          benefit: "Conserva la tienda y el estado al revisar documentos de varias ubicaciones."
        },
        {
          actions: [
            "Selecciona una sola tienda para consultar su stock.",
            "Cambia a sincronización para revisar recibidos, procesados y errores.",
            "Ten en cuenta la conexión antes de interpretar las cantidades."
          ],
          example: "Ejemplo orientativo: una tienda está sin internet. Consulta lo recibido y revisa los contadores, sin asumir que el stock incluye los últimos cambios locales.",
          benefit: "Distingue información recibida de una garantía de actualización instantánea."
        },
        {
          actions: [
            "Accede con la cuenta y el contexto de empresa autorizados.",
            "Revisa licencias y facturas del servicio si tu cuenta tiene permiso.",
            "Con acceso a soporte, abre o continúa la conversación de una solicitud."
          ],
          example: "Ejemplo orientativo: buscas una factura del servicio y necesitas aclararla. Con los permisos correspondientes, consulta su referencia y añádela a tu solicitud de soporte.",
          benefit: "Separa las facturas del servicio de los documentos de venta de tus tiendas."
        }
      ]
    ]
  },
  en: {
    stepBodies: [
      ["Prepare the receipt, check the total and distinguish money collected from authorised outstanding amounts.", "Check stock and customer receivables while working from the keyboard.", "Distinguish a sale recorded in store from deliveries pending to external services."],
      ["Connect stock, movements and reports before deciding on a purchase or transfer.", "Map columns and review the preview before applying a product import.", "Configure each account's tasks without giving everyone administration access."],
      ["Count physical units and review differences against recorded stock.", "Compare goods with confirmed documents without duplicating the stock entry.", "Move units from reserve to the store with an authorised transfer."],
      ["Filter received documents by store, date, type and status.", "Check stock and synchronisation counts without assuming a disconnected store is up to date.", "Review licences, service invoices and support conversations within your permissions."]
    ],
    stepDetails: [
      [
        {
          actions: [
            "Add products and review the receipt's quantities and total.",
            "Choose cash from the enabled methods and check the change.",
            "Manage a customer's outstanding amount only with the required authorisations."
          ],
          example: "Illustrative example: total 2.50, cash entered 1.00 and 1.50 left before completion. No debt is created automatically: leaving it pending needs an identified customer and authorisation.",
          benefit: "Distinguish money collected from the amount a customer still owes."
        },
        {
          actions: [
            "Select the product the customer is asking about.",
            "Press F5 to check stock for that product.",
            "With the customer selected, open their receivables with Ctrl+D."
          ],
          example: "Illustrative example: a customer asks about another unit and an earlier debt. Check the product with F5 and their account with Ctrl+D, within your permissions.",
          benefit: "Answer both enquiries while keeping the context of the sale."
        },
        {
          actions: [
            "Prepare the receipt with access to the store service.",
            "Open Operational status to review the available states.",
            "Distinguish pending, sent and failed synchronisation deliveries."
          ],
          example: "Illustrative example: at closing, a sale is recorded but its delivery is pending. Check the queue; the local service is needed and external deliveries require internet.",
          benefit: "Do not confuse a recorded sale with its delivery to external services."
        }
      ],
      [
        {
          actions: [
            "Select the product and check its stock by warehouse.",
            "Review its movements to understand stock coming in and going out.",
            "Compare sales activity before deciding how to replenish."
          ],
          example: "Illustrative example: a product is running low in store. Check reserve stock, movements and sales before deciding on a purchase or warehouse transfer.",
          benefit: "Distinguish a purchasing need from replenishment between warehouses."
        },
        {
          actions: [
            "Load the sheet and map the code, name and price columns.",
            "Review the preview, matches and rows with errors.",
            "Check the accepted data before applying changes."
          ],
          example: "Illustrative example: a sheet contains new products and price changes. Check the mapping and accepted rows before applying them to the catalogue.",
          benefit: "Find data that needs review before changing products."
        },
        {
          actions: [
            "Identify each user's tasks before assigning their role.",
            "Configure permissions for the functions they need.",
            "Review allowed operations before saving access settings."
          ],
          example: "Illustrative configuration example: checkout and administration need different tasks. Review who can maintain the catalogue and who only uses their assigned functions.",
          benefit: "Match each account's access to its tasks and responsibilities."
        }
      ],
      [
        {
          actions: [
            "Select the warehouse and start or reopen its count.",
            "Scan the product and save the quantity actually counted.",
            "Review expected, counted and difference quantities before confirming."
          ],
          example: "Illustrative example: recorded stock is 10 units and you count 8. Save the count and review the 2-unit difference before confirming.",
          benefit: "Base the update on a reviewed count rather than an assumed figure."
        },
        {
          actions: [
            "Open a confirmed inbound delivery note or invoice.",
            "Scan the goods and review missing or surplus quantities.",
            "Explicitly confirm differences if you complete the check."
          ],
          example: "Illustrative example: a confirmed document lists 12 units and you find 11. Review the missing unit; the check does not record another stock entry.",
          benefit: "Find the discrepancy without duplicating the receiving movement."
        },
        {
          actions: [
            "Check the product's quantities and available reserve stock.",
            "Choose source and destination warehouses and a quantity to move.",
            "With the required permission, record the transfer and review the result."
          ],
          example: "Illustrative example: the store needs 2 units available in reserve. Transfer them between the chosen warehouses and review their resulting quantities.",
          benefit: "Keep a record of where the units leave and where they arrive."
        }
      ],
      [
        {
          actions: [
            "Select stores your account is authorised to view.",
            "Filter documents by date, type, status or number.",
            "Check the store, status and total for each result."
          ],
          example: "Illustrative example: management searches invoices from two stores for the same period. Each row retains the store and status of the received document.",
          benefit: "Keep each document's store and status clear across locations."
        },
        {
          actions: [
            "Select a single store to check its stock.",
            "Switch to synchronisation to review received, processed and error counts.",
            "Consider the connection before interpreting quantities."
          ],
          example: "Illustrative example: a store has no internet. Check received data and the counters without assuming stock includes the latest local changes.",
          benefit: "Distinguish received information from a guarantee of instant updates."
        },
        {
          actions: [
            "Sign in with the authorised account and company context.",
            "Review licences and service invoices if your account has permission.",
            "With support access, open or continue a request's conversation."
          ],
          example: "Illustrative example: you need to clarify a service invoice. With the relevant permissions, check its reference and add it to your support request.",
          benefit: "Keep service invoices distinct from your stores' sales documents."
        }
      ]
    ]
  },
  zh: {
    stepBodies: [
      ["准备小票、核对总额，并区分实际收款与获授权的待收金额。", "使用键盘查询库存与客户欠款，延续当前收银操作。", "区分已在门店登记的销售与尚未发送至外部服务的数据。"],
      ["结合库存、流转记录和报表，决定采购或调拨。", "导入商品前，映射列并检查预览结果。", "按账号配置任务权限，而不是向所有人开放管理权限。"],
      ["清点实物数量，并核对与库存记录的差异。", "核对货物与已确认单据，避免重复记录库存入库。", "通过获授权的调拨，将商品从储备仓移至门店仓。"],
      ["按门店、日期、类型和状态筛选已接收的单据。", "查看库存及同步计数，不将断网门店的数据视为已更新。", "按权限查看许可证、服务账单与支持对话。"]
    ],
    stepDetails: [
      [
        {
          actions: [
            "添加商品并核对小票数量与总额。",
            "从已启用的方式中选择现金，并检查找零。",
            "仅在取得所需授权后处理指定客户的待收金额。"
          ],
          example: "示例情境：总额 2.50，现金录入 1.00，完成销售前还需收取 1.50。余额不会自动成为客户欠款；挂账需要已识别客户及相应授权。",
          benefit: "区分实际收到的款项与客户尚未支付的金额。"
        },
        {
          actions: [
            "选择客户正在询问的商品。",
            "按 F5 查询该商品的库存。",
            "选择客户后，按 Ctrl+D 查看其欠款。"
          ],
          example: "示例情境：客户询问是否还有一件商品，以及之前的欠款。在权限允许范围内，按 F5 查库存，按 Ctrl+D 查客户账户。",
          benefit: "保留当前销售背景，同时解答商品与客户账户问题。"
        },
        {
          actions: [
            "连接门店服务并准备小票。",
            "打开运行状态，查看可用的状态信息。",
            "区分待发送、已发送及同步失败的数据。"
          ],
          example: "示例情境：结束营业时，一笔销售已登记但仍待发送。查看队列；本地作业需要门店服务，向外部服务发送需要互联网。",
          benefit: "不将已登记的销售误认为已经送达外部服务。"
        }
      ],
      [
        {
          actions: [
            "选择商品并按仓库查看库存。",
            "查看流转记录，了解商品入库与出库。",
            "结合销售情况，决定如何补货。"
          ],
          example: "示例情境：门店某款商品即将售罄。先查看储备库存、流转记录和销售情况，再决定采购或仓间调拨。",
          benefit: "区分需要采购的情况与可通过仓间调拨完成的补货。"
        },
        {
          actions: [
            "加载表格，并映射编码、名称和价格列。",
            "检查预览、匹配结果和出错行。",
            "应用变更前核对已接受的数据。"
          ],
          example: "示例情境：表格中既有新商品，也有价格变更。应用到商品目录前，先检查列映射和已接受的行。",
          benefit: "修改商品前，发现需要进一步核对的数据。"
        },
        {
          actions: [
            "分配角色前，明确每位用户需要完成的任务。",
            "为所需功能配置相应权限。",
            "保存访问设置前，检查允许执行的操作。"
          ],
          example: "配置示例情境：收银与行政人员的任务不同。检查谁可维护商品目录，谁只使用分配给自己的功能。",
          benefit: "使每个账号的访问权限与任务和职责相匹配。"
        }
      ],
      [
        {
          actions: [
            "选择仓库，新建或重新打开其盘点。",
            "扫描商品并保存实际清点的数量。",
            "确认前核对预计数量、实盘数量和差异。"
          ],
          example: "示例情境：系统记录 10 件，您实际数到 8 件。保存盘点数量，确认前检查相差的 2 件。",
          benefit: "依据核对后的实盘数量更新，而不是假定的数字。"
        },
        {
          actions: [
            "打开已确认的入库送货单或发票。",
            "扫描货物并检查缺少或多出的数量。",
            "若结束核查时仍有差异，需明确确认。"
          ],
          example: "示例情境：已确认单据列明 12 件，您实际找到 11 件。检查缺少的 1 件；核查不会再次登记库存入库。",
          benefit: "发现数量差异，同时避免重复记录收货流转。"
        },
        {
          actions: [
            "查看商品数量和可用的储备库存。",
            "选择来源仓库、目标仓库及调拨数量。",
            "具备所需权限后，登记调拨并检查结果。"
          ],
          example: "示例情境：门店需要的 2 件商品在储备仓有库存。将其调拨至所选门店仓，并检查两个仓库的结果数量。",
          benefit: "记录商品从哪里调出、向哪里调入。"
        }
      ],
      [
        {
          actions: [
            "选择您的账号获授权查看的门店。",
            "按日期、类型、状态或单号筛选单据。",
            "核对每项结果的门店、状态和总额。"
          ],
          example: "示例情境：管理层查询两家门店同一时期的发票。每一行都保留已接收单据所属的门店和状态。",
          benefit: "跨门店查看时，保留每张单据的门店和状态。"
        },
        {
          actions: [
            "只选择一家门店，查看其库存。",
            "切换至同步，查看已接收、已处理和错误计数。",
            "解读库存数量时考虑网络连接情况。"
          ],
          example: "示例情境：一家门店无法联网。查看已接收数据和计数，但不假定库存已包含门店最新的本地变更。",
          benefit: "区分已接收的信息与即时更新的保证。"
        },
        {
          actions: [
            "使用已授权账号和公司上下文登录。",
            "账号有权限时，查看许可证和服务账单。",
            "具有支持权限时，创建请求或继续其对话。"
          ],
          example: "示例情境：您需要咨询一张服务账单。具备相应权限后，查看其参考编号，并将其写入支持请求。",
          benefit: "将服务账单与您门店的销售单据明确区分。"
        }
      ]
    ]
  }
} as const satisfies Record<MarketingLanguage, TourExamples>;

export function getTourExamples(language: MarketingLanguage) {
  return tourExamples[language];
}
