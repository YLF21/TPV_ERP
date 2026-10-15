import type { ApplicationGuideContent, ApplicationId } from "./applicationGuideTypes";
import type { MarketingLanguage } from "./marketingI18n";

export const appGuideOrder = ["venta", "gestion", "pda", "saas"] as const satisfies readonly ApplicationId[];

const content: Record<MarketingLanguage, ApplicationGuideContent> = {
  es: {
    chooseLabel: "Elige la aplicación de tu equipo",
    sectionLabel: "Explora la aplicación",
    sections: { overview: "Qué resuelve", workflow: "Así se utiliza", setup: "Qué necesitas", questions: "Dudas frecuentes" },
    audienceLabel: "Pensada para",
    tasksLabel: "En tu día a día",
    connectionLabel: "Conexión y datos",
    worksWithLabel: "Cómo se conecta",
    requirementsLabel: "Antes de empezar",
    exampleNote: "Ejemplo ilustrativo: no es una operación ni un caso de cliente real.",
    previewNote: "Interfaz real en español · Datos de demostración",
    tourAction: "Ver recorrido",
    demoAction: "Solicitar demo de esta app",
    consultAction: "Consultar la implantación",
    scopeNote: "Funciones y módulos según configuración. Confirma compatibilidad y alcance antes de contratar.",
    apps: {
      venta: {
        outcome: "Del artículo al cobro, con el ticket y la caja dentro del mismo flujo.",
        audience: "Personal de mostrador y responsables de caja de una o varias tiendas.",
        tasks: [
          { title: "Preparar la venta", body: "Añade artículos, selecciona el cliente y revisa cantidades e importes." },
          { title: "Cobrar y documentar", body: "Usa los medios habilitados y emite el documento de venta correspondiente." },
          { title: "Revisar la caja", body: "Consulta movimientos, devoluciones y ventas pendientes según tus permisos." }
        ],
        workflow: [
          { title: "Prepara el ticket", body: "Un cliente lleva dos artículos: añádelos y comprueba el total." },
          { title: "Registra el cobro", body: "Selecciona efectivo y revisa el importe recibido y el cambio." },
          { title: "Consulta la operación", body: "Finaliza y localiza la venta registrada para su seguimiento." }
        ],
        requirements: ["Equipo Windows de 64 bits compatible.", "Instalación de tienda y terminal configurados.", "Usuario autorizado y periféricos revisados."],
        connection: "Necesita acceso al servicio local de la tienda. La sincronización central y los servicios externos requieren conexión; no todas las funciones son offline.",
        worksWith: "Gestión mantiene catálogo y consulta la actividad; SaaS muestra la información recibida de las tiendas vinculadas.",
        questions: [
          { question: "¿Sirve con cualquier terminal de tarjeta?", answer: "No automáticamente. Hay que confirmar proveedor, integración, servicio y configuración del terminal antes de utilizar pagos reales." },
          { question: "¿Puedo cobrar una venta más adelante?", answer: "Hay ventas pendientes y cobro posterior por los medios habilitados, con cliente identificado y los permisos necesarios." }
        ]
      },
      gestion: {
        outcome: "Relaciona catálogo, existencias y actividad comercial para preparar mejor las decisiones del negocio.",
        audience: "Gerencia, administración y responsables de productos, compras o almacén.",
        tasks: [
          { title: "Organizar el catálogo", body: "Revisa productos, precios, promociones, clientes y proveedores desde administración." },
          { title: "Entender las existencias", body: "Consulta stock por almacén, movimientos e informes de actividad." },
          { title: "Preparar los datos", body: "Revisa hojas Excel antes de importar y asigna permisos por función." }
        ],
        workflow: [
          { title: "Revisa un artículo", body: "Antes de una compra, consulta sus existencias en cada almacén." },
          { title: "Contrasta el contexto", body: "Revisa movimientos y ventas del periodo para entender la necesidad." },
          { title: "Registra la operación", body: "Prepara el documento o movimiento correspondiente y comprueba su resultado." }
        ],
        requirements: ["Equipo Windows de 64 bits compatible.", "Acceso al servicio de tienda configurado.", "Cuenta con permisos para las tareas previstas."],
        connection: "La aplicación de escritorio necesita acceso al servicio del negocio. La información central depende de la conexión y de la sincronización configurada.",
        worksWith: "Mantiene la base operativa usada por Venta y permite autorizar dispositivos PDA y revisar sus tareas de almacén.",
        questions: [
          { question: "¿Puedo importar mi Excel directamente?", answer: "La importación requiere revisar estructura, campos y datos. El alcance de una migración se valida con una muestra antes de aplicarla." },
          { question: "¿Todos ven las mismas funciones?", answer: "El acceso se organiza por usuarios, roles y permisos; cada perfil debe configurarse según su responsabilidad." }
        ]
      },
      pda: {
        outcome: "Lleva el recuento y la consulta de producto al lugar donde está la mercancía.",
        audience: "Equipos que reciben, cuentan, comprueban y reponen mercancía entre almacenes.",
        tasks: [
          { title: "Contar existencias", body: "Escanea artículos, introduce cantidades y revisa diferencias antes de confirmar." },
          { title: "Comprobar documentos", body: "Contrasta mercancía con un documento confirmado sin crear otra entrada." },
          { title: "Preparar reposición", body: "Consulta stock y registra transferencias entre almacenes de origen y destino." }
        ],
        workflow: [
          { title: "Elige el almacén", body: "Para contar una estantería, selecciona el almacén que vas a revisar." },
          { title: "Anota el recuento", body: "Escanea cada artículo e introduce la cantidad que encuentras físicamente." },
          { title: "Revisa y confirma", body: "Comprueba las diferencias antes de aplicar el inventario con tu autorización." }
        ],
        requirements: ["Dispositivo Android con navegador compatible.", "Dispositivo vinculado y aprobado desde Gestión.", "Red y acceso al servicio de tienda disponibles."],
        connection: "Es una aplicación web conectada al servicio de la tienda. Necesita red y permisos; no se ofrece como APK público ni como almacén completamente offline.",
        worksWith: "Gestión autoriza el dispositivo y comparte los almacenes y movimientos disponibles. La PDA no sustituye a la caja de Venta.",
        questions: [
          { question: "¿Necesito descargar una APK?", answer: "No. El acceso web se configura durante la implantación y se utiliza desde el dispositivo autorizado." },
          { question: "¿Comprobar una recepción duplica el stock?", answer: "No. La comprobación contrasta un documento ya confirmado; no registra una nueva entrada de mercancía." }
        ]
      },
      saas: {
        outcome: "Consulta la actividad recibida de tus tiendas y la información del servicio desde un portal cliente.",
        audience: "Dirección y responsables autorizados que necesitan consultar una o varias tiendas vinculadas.",
        tasks: [
          { title: "Revisar documentos", body: "Consulta operaciones de las tiendas autorizadas, conservando su contexto de origen." },
          { title: "Consultar una tienda", body: "Selecciona un establecimiento y revisa su stock y estado de sincronización." },
          { title: "Consultar el servicio", body: "Accede a licencias, facturas del servicio SaaS y soporte según permisos." }
        ],
        workflow: [
          { title: "Accede a tu portal", body: "La persona responsable entra con la cuenta habilitada para su organización." },
          { title: "Selecciona una tienda", body: "Consulta sus existencias y la última información recibida de esa tienda." },
          { title: "Comprueba la actualidad", body: "Revisa la sincronización antes de interpretar el dato o consultar al equipo." }
        ],
        requirements: ["Navegador compatible y conexión a internet.", "Organización y cuenta cliente habilitadas.", "Tiendas vinculadas y permisos de consulta."],
        connection: "Los datos centrales reflejan lo recibido por sincronización. Una tienda desconectada puede mostrar información anterior; no se promete actualización instantánea universal.",
        worksWith: "Recibe información de la instalación de tienda. Complementa Venta y Gestión: no reemplaza su operativa local ni es el panel interno de esPOS.",
        questions: [
          { question: "¿Es el acceso de administración interna?", answer: "No. Es el portal autorizado de tu organización, separado de las herramientas internas del proveedor." },
          { question: "¿Puedo modificar todo desde la nube?", answer: "El portal descrito es de consulta y servicio. No implica edición remota completa ni contabilidad consolidada." }
        ]
      }
    },
    gettingStarted: {
      title: "De tus necesidades a una configuración concreta",
      body: "Puedes solicitar una sola aplicación, combinar varias o consultar el paquete completo.",
      steps: [
        { title: "Cuéntanos tu operativa", body: "Indica qué tareas quieres mejorar, cuántas tiendas tienes y qué equipos las realizan." },
        { title: "Revisamos el encaje", body: "Validamos dispositivos, conexión, permisos y una muestra de los datos que quieras trasladar." },
        { title: "Concretamos la demostración", body: "Revisamos las aplicaciones y la configuración adecuadas con ejemplos de tu actividad." }
      ],
      note: "La compatibilidad, los módulos y las condiciones de implantación se confirman para cada negocio."
    }
  },
  en: {
    chooseLabel: "Choose your team's application",
    sectionLabel: "Explore the application",
    sections: { overview: "What it solves", workflow: "How it works", setup: "What you need", questions: "Common questions" },
    audienceLabel: "Designed for",
    tasksLabel: "Your everyday tasks",
    connectionLabel: "Connection and data",
    worksWithLabel: "How it connects",
    requirementsLabel: "Before you start",
    exampleNote: "Illustrative example: not an actual transaction or customer case.",
    previewNote: "Real interface in Spanish · Demonstration data",
    tourAction: "View the tour",
    demoAction: "Request a demo of this app",
    consultAction: "Discuss your setup",
    scopeNote: "Features and modules depend on configuration. Confirm compatibility and scope before purchasing.",
    apps: {
      venta: {
        outcome: "From selecting products to payment, with the receipt and cash register in one workflow.",
        audience: "Counter staff and cash register supervisors in one or several stores.",
        tasks: [
          { title: "Prepare the sale", body: "Add products, select the customer and check quantities and amounts." },
          { title: "Take and record payment", body: "Use enabled methods and issue the appropriate sales document." },
          { title: "Review the register", body: "Check movements, returns and pending sales within your permissions." }
        ],
        workflow: [
          { title: "Prepare the receipt", body: "A customer brings two items: add them and check the total." },
          { title: "Record the payment", body: "Select cash and check the amount received and change due." },
          { title: "Find the transaction", body: "Finish and locate the recorded sale for follow-up." }
        ],
        requirements: ["A compatible 64-bit Windows computer.", "A configured store installation and terminal.", "An authorized user and checked peripherals."],
        connection: "Needs access to the local store service. Central synchronization and external services need a connection; not every feature works offline.",
        worksWith: "Management maintains the catalogue and reviews activity; SaaS shows information received from linked stores.",
        questions: [
          { question: "Does it work with every card terminal?", answer: "Not automatically. The provider, integration, service and terminal configuration must be confirmed before taking real payments." },
          { question: "Can I collect payment later?", answer: "Pending sales and later collection use enabled methods, an identified customer and the required permissions." }
        ]
      },
      gestion: {
        outcome: "Connect catalogue, stock and sales activity to make better-informed business decisions.",
        audience: "Managers, administration and teams responsible for products, purchasing or warehouses.",
        tasks: [
          { title: "Organize your catalogue", body: "Review products, prices, promotions, customers and suppliers in the administration app." },
          { title: "Understand your stock", body: "Check warehouse-level stock, movements and activity reports." },
          { title: "Prepare your data", body: "Review Excel sheets before importing and assign permissions by responsibility." }
        ],
        workflow: [
          { title: "Check a product", body: "Before purchasing, look up its stock in each warehouse." },
          { title: "Review the context", body: "Check movements and sales for the period to understand demand." },
          { title: "Record the operation", body: "Prepare the relevant document or movement and check its result." }
        ],
        requirements: ["A compatible 64-bit Windows computer.", "Configured access to the store service.", "An account authorized for the intended tasks."],
        connection: "The desktop app needs access to the business service. Central information depends on connectivity and the configured synchronization.",
        worksWith: "Maintains the operational data used by Sales and lets you authorize PDA devices and review their warehouse tasks.",
        questions: [
          { question: "Can I import my Excel file directly?", answer: "Importing requires checking the structure, fields and data. Migration scope is validated with a sample before applying it." },
          { question: "Does everyone see the same features?", answer: "Access is organized through users, roles and permissions; each profile should match its responsibilities." }
        ]
      },
      pda: {
        outcome: "Bring stock counting and product lookup to the place where goods are stored.",
        audience: "Teams receiving, counting, checking and replenishing goods between warehouses.",
        tasks: [
          { title: "Count stock", body: "Scan products, enter quantities and review differences before confirming." },
          { title: "Check documents", body: "Compare goods with a confirmed document without creating another receipt." },
          { title: "Prepare replenishment", body: "Check stock and record transfers between source and destination warehouses." }
        ],
        workflow: [
          { title: "Choose the warehouse", body: "To count a shelf, select the warehouse you want to check." },
          { title: "Record the count", body: "Scan each product and enter the quantity physically present." },
          { title: "Review and confirm", body: "Check differences before applying the stock count with your authorization." }
        ],
        requirements: ["An Android device with a compatible browser.", "A device linked and approved in Management.", "Network access to the store service."],
        connection: "A web app connected to the store service. It needs network access and permissions; it is not a public APK or a fully offline warehouse app.",
        worksWith: "Management authorizes the device and shares available warehouses and movements. PDA does not replace Sales at checkout.",
        questions: [
          { question: "Do I need to download an APK?", answer: "No. Web access is configured during setup and used from the authorized device." },
          { question: "Does checking a delivery duplicate stock?", answer: "No. Checking compares goods against an already confirmed document; it does not record another goods receipt." }
        ]
      },
      saas: {
        outcome: "Review activity received from your stores and service information through a customer portal.",
        audience: "Managers and authorized supervisors consulting one or several linked stores.",
        tasks: [
          { title: "Review documents", body: "Consult authorized stores' transactions while retaining their source-store context." },
          { title: "Check a store", body: "Select one location and review its stock and synchronization status." },
          { title: "Check your service", body: "Access licenses, SaaS service invoices and support within your permissions." }
        ],
        workflow: [
          { title: "Open your portal", body: "The responsible person signs in with the organization's enabled account." },
          { title: "Select a store", body: "Check its stock and the latest information received from that store." },
          { title: "Check data freshness", body: "Review synchronization before interpreting the data or contacting the team." }
        ],
        requirements: ["A compatible browser and internet connection.", "An enabled organization and customer account.", "Linked stores and viewing permissions."],
        connection: "Central data reflects information received through synchronization. A disconnected store may show older information; instant updates are not universally guaranteed.",
        worksWith: "Receives information from the store installation. Complements Sales and Management without replacing local operation or exposing esPOS's internal administration panel.",
        questions: [
          { question: "Is this the internal administrator login?", answer: "No. It is your organization's authorized customer portal, separate from the provider's internal tools." },
          { question: "Can I edit everything from the cloud?", answer: "The portal described provides consultation and service access, not complete remote editing or consolidated accounting." }
        ]
      }
    },
    gettingStarted: {
      title: "From your needs to a specific setup",
      body: "Request one application, combine several or discuss the complete package.",
      steps: [
        { title: "Describe your operation", body: "Tell us which tasks need improvement, how many stores you have and who uses the equipment." },
        { title: "Check the fit", body: "We review devices, connectivity, permissions and a sample of any data you want to move." },
        { title: "Shape the demonstration", body: "We review suitable applications and configuration using examples from your activity." }
      ],
      note: "Compatibility, modules and implementation conditions are confirmed for each business."
    }
  },
  zh: {
    chooseLabel: "选择适合团队的应用",
    sectionLabel: "了解应用详情",
    sections: { overview: "解决什么问题", workflow: "如何使用", setup: "使用条件", questions: "常见问题" },
    audienceLabel: "适用团队",
    tasksLabel: "日常工作",
    connectionLabel: "连接与数据",
    worksWithLabel: "应用间如何配合",
    requirementsLabel: "开始前请确认",
    exampleNote: "以下仅为使用场景示例，并非真实交易或客户案例。",
    previewNote: "西班牙语真实界面 · 演示数据",
    tourAction: "查看操作导览",
    demoAction: "申请此应用的演示",
    consultAction: "咨询部署方案",
    scopeNote: "功能和模块取决于具体配置。购买前请确认兼容性和实施范围。",
    apps: {
      venta: {
        outcome: "从选择商品到完成收款，在同一流程中处理销售小票与收银。",
        audience: "单店或多门店的柜台员工及收银负责人。",
        tasks: [
          { title: "准备销售", body: "添加商品、选择客户，并核对数量和金额。" },
          { title: "收款并生成单据", body: "使用已启用的支付方式，生成相应的销售单据。" },
          { title: "核对收银记录", body: "根据账户权限查询资金变动、退货和待收款销售。" }
        ],
        workflow: [
          { title: "准备小票", body: "客户购买两件商品：添加商品并核对总额。" },
          { title: "登记收款", body: "选择现金，核对实收金额与应找零金额。" },
          { title: "查询交易", body: "完成销售后，查找已登记的交易以便后续跟进。" }
        ],
        requirements: ["兼容的 64 位 Windows 电脑。", "已配置的门店安装环境与收银终端。", "授权用户及已检查的外设。"],
        connection: "需要能够访问门店本地服务。中央同步及外部服务需要网络连接，并非所有功能均可离线使用。",
        worksWith: "管理应用维护商品资料并查看业务活动；SaaS 展示从已关联门店接收到的信息。",
        questions: [
          { question: "可以直接连接任何刷卡终端吗？", answer: "不能默认兼容。使用真实支付前，需要确认服务商、集成、相关服务及终端配置。" },
          { question: "可以先销售、之后再收款吗？", answer: "支持待收款销售及后续收款，但需指定客户、使用已启用的收款方式，并具备相应权限。" }
        ]
      },
      gestion: {
        outcome: "将商品资料、库存与销售活动联系起来，为业务决策提供依据。",
        audience: "管理层、行政人员，以及负责商品、采购或仓库的团队。",
        tasks: [
          { title: "整理商品资料", body: "在管理应用中查看商品、价格、促销、客户及供应商。" },
          { title: "了解库存情况", body: "按仓库查询库存、出入库变动及业务报表。" },
          { title: "准备业务数据", body: "导入前检查 Excel 表格，并按工作职责分配权限。" }
        ],
        workflow: [
          { title: "查看商品", body: "采购前，先查询该商品在各仓库的现有库存。" },
          { title: "分析业务背景", body: "结合期间内的库存变动和销售情况，判断补货需求。" },
          { title: "登记操作", body: "准备相应单据或库存变动，并核对操作结果。" }
        ],
        requirements: ["兼容的 64 位 Windows 电脑。", "已配置的门店服务访问环境。", "具备所需操作权限的账户。"],
        connection: "桌面应用需要能够访问业务服务。中央数据的可用性取决于网络连接及已配置的同步机制。",
        worksWith: "维护销售应用使用的业务数据，也可授权 PDA 设备并查看其仓库作业。",
        questions: [
          { question: "现有 Excel 文件能直接导入吗？", answer: "需要先检查表格结构、字段和数据。正式迁移前，会通过样本确认迁移范围。" },
          { question: "所有员工都能看到相同功能吗？", answer: "访问范围由用户、角色和权限决定，应按每个岗位的实际职责配置。" }
        ]
      },
      pda: {
        outcome: "在货物所在的位置完成库存盘点与商品查询，贴近仓库现场工作。",
        audience: "负责收货、盘点、核对及仓库间补货的团队。",
        tasks: [
          { title: "盘点库存", body: "扫描商品、输入数量，并在确认前检查差异。" },
          { title: "核对单据", body: "按已确认单据核对实物，不重复生成入库记录。" },
          { title: "准备补货", body: "查询库存，并登记来源仓与目标仓之间的调拨。" }
        ],
        workflow: [
          { title: "选择仓库", body: "盘点某个货架前，先选择本次要核对的仓库。" },
          { title: "记录实盘数量", body: "逐一扫描商品，并填写现场实际清点的数量。" },
          { title: "复核并确认", body: "先检查差异，再根据账户权限确认盘点结果。" }
        ],
        requirements: ["配备兼容浏览器的 Android 设备。", "已在管理应用中绑定并批准的设备。", "可用网络及门店服务访问环境。"],
        connection: "这是连接门店服务的网页应用，需要网络和操作权限；不提供公开 APK，也不是完全离线的仓库应用。",
        worksWith: "管理应用负责授权设备并提供可访问的仓库和库存变动。PDA 不替代销售应用的收银工作。",
        questions: [
          { question: "需要下载 APK 吗？", answer: "不需要。部署时配置网页访问方式，通过已授权设备使用。" },
          { question: "核对收货会重复增加库存吗？", answer: "不会。核对功能对比的是已确认单据与实物，不会再登记一次入库。" }
        ]
      },
      saas: {
        outcome: "通过客户门户查询已接收的门店业务数据及服务信息。",
        audience: "需要查询一个或多个已关联门店的管理层及授权负责人。",
        tasks: [
          { title: "查看业务单据", body: "查询授权门店的交易，并保留每笔交易所属门店的信息。" },
          { title: "查看指定门店", body: "选择一家门店，查询其库存与同步状态。" },
          { title: "查看服务信息", body: "根据账户权限查看许可证、SaaS 服务账单及支持入口。" }
        ],
        workflow: [
          { title: "登录客户门户", body: "负责人使用已为所属组织开通的账户登录。" },
          { title: "选择门店", body: "查看该门店库存及最近一次接收到的信息。" },
          { title: "核对数据时效", body: "判断数据或联系团队前，先检查同步状态。" }
        ],
        requirements: ["兼容浏览器及互联网连接。", "已开通的组织与客户账户。", "已关联门店及相应查询权限。"],
        connection: "中央数据反映通过同步实际接收到的信息。门店断网时可能显示较早数据，不承诺所有信息均即时更新。",
        worksWith: "接收门店安装环境的数据，补充销售与管理应用的功能；不替代门店本地作业，也不是 esPOS 内部管理后台。",
        questions: [
          { question: "这是内部管理员的登录入口吗？", answer: "不是。这是面向您所属组织的授权客户门户，与服务商内部工具分开。" },
          { question: "可以在云端修改所有内容吗？", answer: "这里介绍的门户用于查询及服务访问，不代表完整远程编辑或合并会计功能。" }
        ]
      }
    },
    gettingStarted: {
      title: "从实际需求出发，明确具体配置",
      body: "可以申请一款应用、组合多款，或咨询完整套装。",
      steps: [
        { title: "介绍您的业务", body: "说明希望改善的工作、门店数量，以及各设备由哪些团队使用。" },
        { title: "确认适配条件", body: "核对设备、网络、权限，以及需要迁移的数据样本。" },
        { title: "确定演示内容", body: "结合您的业务场景，讨论适合的应用及配置。" }
      ],
      note: "兼容性、功能模块及实施条件需按每家商户的情况确认。"
    }
  }
};

export function getApplicationGuides(language: MarketingLanguage): ApplicationGuideContent {
  return content[language];
}
