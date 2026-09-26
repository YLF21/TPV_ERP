export type MarketingLanguage = "es" | "en" | "zh";

const content = {
  es: {
    language: "Idioma",
    navigation: ["Inicio", "Solución", "Funcionalidades", "Apps", "Ventajas", "Contacto"],
    common: {
      download: "Descargar aplicación",
      demo: "Solicitar demostración",
      applications: "Aplicaciones",
      advantages: "Ventajas",
      footer: "Venta, gestión y almacén conectados para comercios que no paran."
    },
    home: {
      kicker: "Tu negocio, sin límites",
      title: "Todo tu negocio.",
      titleAccent: "Una sola plataforma.",
      lead: "Venta, gestión y almacén conectados.",
      body: "Controla una o varias tiendas desde un sistema sencillo, seguro y preparado para crecer contigo.",
      checks: ["Fácil de usar", "Escalable y modular", "Soporte experto"],
      benefits: [
        ["Multi-tienda", "Una o varias tiendas, todo bajo control."],
        ["Siempre conectado", "Información actualizada desde cualquier lugar."],
        ["Seguro y fiable", "Tu negocio protegido y trazable."],
        ["Soporte experto", "Un equipo cerca cuando lo necesites."]
      ],
      introKicker: "Una plataforma para el comercio real",
      introTitle: "Rápido en tu tienda.",
      introAccent: "Visible desde cualquier lugar.",
      introBody: "La operativa crítica permanece cerca de tu negocio. La información que necesitas viaja al panel central para darte una visión completa.",
      introLink: "Descubre cómo se conecta"
    },
    solution: {
      kicker: "La solución",
      title: "Una sola información.",
      accent: "Cuatro formas de trabajar.",
      body: "TPV ERP une el mostrador, la oficina, el almacén y la visión central sin obligarte a cambiar la forma en la que funciona tu negocio.",
      principles: [
        ["Local donde importa", "Venta, caja y operación diaria responden desde la instalación de tienda."],
        ["Conectado cuando lo necesitas", "Ventas, stock y estado operativo se sincronizan con el panel central."],
        ["Preparado para crecer", "Añade tiendas, terminales, usuarios y dispositivos manteniendo el control."]
      ],
      bandKicker: "Un ecosistema, no piezas sueltas",
      bandTitle: "Lo que ocurre en la caja actualiza el resto del negocio.",
      bandBody: "Menos duplicados, menos tareas manuales y una trazabilidad común para cada equipo.",
      bandAction: "Conocer las aplicaciones"
    },
    features: {
      kicker: "Funcionalidades",
      title: "Todo lo necesario.",
      accent: "Sin cambiar de herramienta.",
      body: "Desde el primer cobro hasta el análisis de resultados, cada módulo comparte la misma base operativa.",
      groups: [
        ["Venta y caja", "Cobra rápido sin perder el control", "Crea tickets, facturas o albaranes, combina formas de pago y gestiona devoluciones y cierres de caja con trazabilidad.", ["Ventas pendientes y cobros posteriores", "Integración con terminales de pago", "Impresión y recuperación segura"]],
        ["Catálogo y clientes", "La información correcta en cada venta", "Centraliza productos, tarifas, promociones, clientes, socios y proveedores para evitar tareas repetidas y decisiones a ciegas.", ["Precios, ofertas y fidelización", "Importación y exportación Excel", "Historial documental por cliente"]],
        ["Almacén y stock", "Existencias reales, tienda por tienda", "Controla movimientos, recepciones, inventarios y reposición desde Gestión o desde la PDA de almacén.", ["Múltiples almacenes", "Conteos y comprobaciones", "Stock sincronizado"]],
        ["Fiscalidad y seguridad", "Preparado para operar con confianza", "Permisos, auditoría, copias de seguridad y flujos fiscales forman parte del sistema desde su diseño.", ["Preparado para VeriFactu", "Usuarios y permisos", "Trazabilidad de operaciones"]]
      ]
    },
    apps: {
      kicker: "Aplicaciones TPV ERP",
      title: "Una app para cada tarea.",
      accent: "Los mismos datos para todos.",
      body: "Cada equipo trabaja con una interfaz enfocada, mientras el negocio conserva una visión compartida.",
      detailLabels: ["Ideal para", "Trabaja con"],
      items: [
        ["APP Venta", "Tu punto de venta", "Cobros ágiles, caja, tickets, facturas, devoluciones y ventas pendientes en una interfaz preparada para el mostrador.", ["Efectivo, tarjeta y transferencia", "Atajos de teclado", "Operativa local"], "Mostradores con venta rápida", "Tickets, caja, facturación y devoluciones"],
        ["APP Gestión", "El control de tu negocio", "Productos, clientes, proveedores, promociones, almacenes e informes conectados con cada venta.", ["Stock e informes", "Importación Excel", "Roles y permisos"], "Gerencia y administración", "Catálogo, compras, clientes e informes"],
        ["APP PDA", "Movilidad en almacén", "Recepciones, inventarios, reposición y consulta de producto desde dispositivos vinculados de forma segura.", ["Inventarios", "Comprobaciones", "Reposición"], "Equipos de almacén", "Recepción, inventario, ubicación y reposición"],
        ["APP SaaS", "Tu negocio en la nube", "Visión central de tiendas, licencias, ventas, existencias, facturación y soporte desde cualquier lugar.", ["Multi-tienda", "Sincronización", "Portal cliente"], "Dirección y redes de tiendas", "Sucursales, licencias, ventas y soporte"]
      ]
    },
    advantagesPage: {
      kicker: "Ventajas",
      title: "Lo mejor de lo local.",
      accent: "Lo mejor de la nube.",
      body: "Una arquitectura híbrida para que la tienda siga respondiendo y la dirección mantenga una visión central.",
      headers: ["Capacidad", "TPV tradicional", "Solo nube", "TPV ERP"],
      rows: [
        ["Operación en tienda sin internet", "Limitada", "Depende de conexión", "Sí, con operación local"],
        ["Visión central multi-tienda", "No habitual", "Incluida", "Incluida con sincronización"],
        ["Venta, gestión y almacén", "Herramientas separadas", "Según proveedor", "Un ecosistema conectado"],
        ["Dispositivos de almacén", "Integración adicional", "Según proveedor", "APP PDA integrada"],
        ["Control del dato operativo", "Solo local", "Solo nube", "Local y central"],
        ["Copias de seguridad", "Proceso manual", "Incluidas según plan", "Automáticas y centralizadas"],
        ["Crecimiento a nuevas tiendas", "Configuración independiente", "Nueva suscripción", "Modular y escalable"],
        ["Usuarios y permisos", "Control básico", "Según proveedor", "Roles y permisos integrados"],
        ["Fiscalidad y trazabilidad", "Actualizaciones externas", "Según servicio", "Preparado para VeriFactu"]
      ],
      note: "Comparación general por enfoque tecnológico. Las capacidades concretas dependen de la configuración y los módulos contratados."
    },
    contact: {
      kicker: "Contacta con nuestro equipo",
      title: "¿Qué parte de tu negocio quieres",
      accent: "mejorar primero?",
      body: "Elige la solución que más te interesa y cuéntanos tu caso. Prepararemos una demostración adaptada a tu operativa.",
      groupLabel: "Selecciona la aplicación que te interesa",
      trustLabel: "Cómo preparamos la demostración",
      trust: [["Sin compromiso", "Explora sin presiones."], ["Demo adaptada", "A tu negocio y objetivos."], ["Respuesta cercana", "Te acompañamos."]],
      formTag: "Solicita tu demo",
      formTitle: "Cuéntanos un poco más sobre tu negocio",
      formBody: "Te mostraremos cómo TPV ERP puede ayudarte con tus objetivos.",
      product: "Producto de interés",
      name: "Nombre y apellidos",
      namePlaceholder: "Tu nombre",
      company: "Empresa",
      companyPlaceholder: "Nombre de tu empresa",
      email: "Correo electrónico",
      phone: "Teléfono",
      caseLabel: "Cuéntanos brevemente tu caso",
      casePlaceholder: "Número de tiendas, sector y qué te gustaría mejorar…",
      submit: "Quiero una demo",
      talk: "Hablar con el equipo",
      prepared: "Solicitud preparada en tu aplicación de correo.",
      pendingRecipient: "El destinatario comercial se añadirá cuando se configure el canal de contacto definitivo."
    }
  },
  en: {
    language: "Language",
    navigation: ["Home", "Solution", "Features", "Apps", "Benefits", "Contact"],
    common: {
      download: "Download app",
      demo: "Request a demo",
      applications: "Applications",
      advantages: "Benefits",
      footer: "Connected sales, management and inventory for businesses that never stop."
    },
    home: {
      kicker: "Your business, without limits",
      title: "Your whole business.",
      titleAccent: "One single platform.",
      lead: "Sales, management and inventory connected.",
      body: "Manage one or several stores with a simple, secure system designed to grow with you.",
      checks: ["Easy to use", "Scalable and modular", "Expert support"],
      benefits: [
        ["Multi-store", "One or several stores, all under control."],
        ["Always connected", "Up-to-date information from anywhere."],
        ["Secure and reliable", "Your business protected and traceable."],
        ["Expert support", "A team nearby whenever you need it."]
      ],
      introKicker: "A platform for real-world retail",
      introTitle: "Fast in your store.",
      introAccent: "Visible from anywhere.",
      introBody: "Critical operations stay close to your business. The information you need flows to the central dashboard for a complete view.",
      introLink: "See how everything connects"
    },
    solution: {
      kicker: "The solution",
      title: "One source of truth.",
      accent: "Four ways to work.",
      body: "TPV ERP connects the counter, office, warehouse and central view without forcing you to change how your business operates.",
      principles: [
        ["Local where it matters", "Sales, checkout and daily operations respond from the store installation."],
        ["Connected when you need it", "Sales, stock and operational status sync with the central dashboard."],
        ["Ready to grow", "Add stores, terminals, users and devices while staying in control."]
      ],
      bandKicker: "One ecosystem, not separate pieces",
      bandTitle: "What happens at checkout updates the rest of your business.",
      bandBody: "Less duplication, fewer manual tasks and shared traceability for every team.",
      bandAction: "Explore the applications"
    },
    features: {
      kicker: "Features",
      title: "Everything you need.",
      accent: "Without switching tools.",
      body: "From the first payment to performance analysis, every module shares the same operational foundation.",
      groups: [
        ["Sales and checkout", "Take payments quickly and stay in control", "Create receipts, invoices or delivery notes, combine payment methods, and manage returns and cash closing with full traceability.", ["Pending sales and later payments", "Payment terminal integration", "Secure printing and recovery"]],
        ["Catalogue and customers", "The right information in every sale", "Centralise products, pricing, promotions, customers, members and suppliers to avoid repetition and blind decisions.", ["Pricing, offers and loyalty", "Excel import and export", "Document history by customer"]],
        ["Warehouse and stock", "Real stock, store by store", "Manage movements, receipts, counts and replenishment from Management or the warehouse PDA.", ["Multiple warehouses", "Counts and checks", "Synced stock"]],
        ["Tax and security", "Ready to operate with confidence", "Permissions, auditing, backups and tax workflows are built into the system from the start.", ["Ready for VeriFactu", "Users and permissions", "Operation traceability"]]
      ]
    },
    apps: {
      kicker: "TPV ERP applications",
      title: "One app for every task.",
      accent: "The same data for everyone.",
      body: "Each team works in a focused interface while the business keeps one shared view.",
      detailLabels: ["Best for", "Works with"],
      items: [
        ["Sales App", "Your point of sale", "Fast payments, checkout, receipts, invoices, returns and pending sales in an interface built for the counter.", ["Cash, card and bank transfer", "Keyboard shortcuts", "Local operation"], "Fast-paced sales counters", "Receipts, checkout, billing and returns"],
        ["Management App", "Control your business", "Products, customers, suppliers, promotions, warehouses and reports connected to every sale.", ["Stock and reports", "Excel import", "Roles and permissions"], "Managers and administration", "Catalogue, purchasing, customers and reports"],
        ["PDA App", "Mobile warehouse work", "Receipts, inventory, replenishment and product lookup from securely linked devices.", ["Inventory", "Checks", "Replenishment"], "Warehouse teams", "Receiving, inventory, locations and replenishment"],
        ["SaaS App", "Your business in the cloud", "A central view of stores, licences, sales, stock, billing and support from anywhere.", ["Multi-store", "Synchronisation", "Customer portal"], "Management and store networks", "Branches, licences, sales and support"]
      ]
    },
    advantagesPage: {
      kicker: "Benefits",
      title: "The best of local.",
      accent: "The best of cloud.",
      body: "A hybrid architecture that keeps the store responsive while management retains a central view.",
      headers: ["Capability", "Traditional POS", "Cloud only", "TPV ERP"],
      rows: [
        ["In-store operation without internet", "Limited", "Connection dependent", "Yes, with local operation"],
        ["Central multi-store view", "Uncommon", "Included", "Included with synchronisation"],
        ["Sales, management and warehouse", "Separate tools", "Depends on provider", "One connected ecosystem"],
        ["Warehouse devices", "Extra integration", "Depends on provider", "Integrated PDA App"],
        ["Operational data control", "Local only", "Cloud only", "Local and central"],
        ["Backups", "Manual process", "Included depending on plan", "Automatic and centralised"],
        ["Adding new stores", "Independent setup", "New subscription", "Modular and scalable"],
        ["Users and permissions", "Basic control", "Depends on provider", "Integrated roles and permissions"],
        ["Tax and traceability", "External updates", "Depends on service", "Ready for VeriFactu"]
      ],
      note: "A general comparison by technology approach. Specific capabilities depend on configuration and contracted modules."
    },
    contact: {
      kicker: "Talk to our team",
      title: "Which part of your business would you like to",
      accent: "improve first?",
      body: "Choose the solution you are interested in and tell us about your needs. We will prepare a demo tailored to your operation.",
      groupLabel: "Select the application you are interested in",
      trustLabel: "How we prepare your demo",
      trust: [["No commitment", "Explore without pressure."], ["Tailored demo", "Built around your goals."], ["Personal response", "We support you."]],
      formTag: "Request your demo",
      formTitle: "Tell us a little more about your business",
      formBody: "We will show you how TPV ERP can support your goals.",
      product: "Product of interest",
      name: "Full name",
      namePlaceholder: "Your name",
      company: "Company",
      companyPlaceholder: "Company name",
      email: "Email address",
      phone: "Phone",
      caseLabel: "Briefly tell us about your needs",
      casePlaceholder: "Number of stores, industry and what you would like to improve…",
      submit: "Request my demo",
      talk: "Talk to the team",
      prepared: "Your request is ready in your email application.",
      pendingRecipient: "The sales recipient will be added when the final contact channel is configured."
    }
  },
  zh: {
    language: "语言",
    navigation: ["首页", "解决方案", "功能", "应用", "优势", "联系"],
    common: {
      download: "下载应用",
      demo: "申请演示",
      applications: "应用",
      advantages: "优势",
      footer: "连接销售、管理与库存，为持续运营的零售业务而生。"
    },
    home: {
      kicker: "业务无界",
      title: "管理整个业务。",
      titleAccent: "只需一个平台。",
      lead: "销售、管理与库存互联。",
      body: "通过简单、安全且可扩展的系统，管理一家或多家门店。",
      checks: ["简单易用", "灵活扩展", "专业支持"],
      benefits: [
        ["多门店", "一家或多家门店，尽在掌控。"],
        ["始终在线", "随时随地掌握最新信息。"],
        ["安全可靠", "业务受保护，全程可追溯。"],
        ["专业支持", "需要时，团队就在身边。"]
      ],
      introKicker: "为真实零售场景打造的平台",
      introTitle: "门店响应迅速。",
      introAccent: "随处掌握全局。",
      introBody: "关键操作留在门店本地，所需信息同步至中央面板，提供完整业务视图。",
      introLink: "了解如何互联"
    },
    solution: {
      kicker: "解决方案",
      title: "统一的数据。",
      accent: "四种工作方式。",
      body: "TPV ERP 连接收银台、办公室、仓库与中央管理，同时保留您熟悉的运营方式。",
      principles: [
        ["本地关键操作", "销售、收银和日常运营由门店本地系统快速响应。"],
        ["按需连接", "销售、库存与运营状态同步至中央面板。"],
        ["为增长而准备", "增加门店、终端、用户和设备，同时保持掌控。"]
      ],
      bandKicker: "一个生态，而非分散工具",
      bandTitle: "收银台发生的一切都会更新整个业务。",
      bandBody: "减少重复与手工作业，让每个团队共享完整追溯信息。",
      bandAction: "了解应用"
    },
    features: {
      kicker: "功能",
      title: "所需功能齐全。",
      accent: "无需切换工具。",
      body: "从首次收款到业绩分析，所有模块共享同一运营基础。",
      groups: [
        ["销售与收银", "快速收款，始终可控", "创建小票、发票或送货单，组合支付方式，并可追溯地管理退货与结账。", ["挂单与后续收款", "支付终端集成", "安全打印与恢复"]],
        ["商品与客户", "每次销售都有正确信息", "集中管理商品、价格、促销、客户、会员和供应商，减少重复工作。", ["价格、优惠与会员", "Excel 导入导出", "客户单据历史"]],
        ["仓库与库存", "门店库存真实可见", "通过管理端或仓库 PDA 管理出入库、收货、盘点与补货。", ["多仓库", "盘点与核查", "库存同步"]],
        ["税务与安全", "安心运营", "权限、审计、备份与税务流程从系统设计之初即已内置。", ["支持 VeriFactu", "用户与权限", "操作可追溯"]]
      ]
    },
    apps: {
      kicker: "TPV ERP 应用",
      title: "每项任务都有专用应用。",
      accent: "所有人共享同一数据。",
      body: "每个团队使用专注的界面，同时业务保持统一视图。",
      detailLabels: ["适合", "覆盖业务"],
      items: [
        ["销售应用", "您的销售终端", "快速收款、收银、小票、发票、退货与挂单，专为柜台操作设计。", ["现金、刷卡与转账", "键盘快捷键", "本地运行"], "高频销售柜台", "小票、收银、开票与退货"],
        ["管理应用", "掌控业务", "商品、客户、供应商、促销、仓库与报表连接每一笔销售。", ["库存与报表", "Excel 导入", "角色与权限"], "管理层与行政人员", "商品、采购、客户与报表"],
        ["PDA 应用", "移动仓库作业", "通过安全连接的设备完成收货、盘点、补货与商品查询。", ["盘点", "核查", "补货"], "仓库团队", "收货、盘点、库位与补货"],
        ["SaaS 应用", "云端业务", "随时查看门店、许可证、销售、库存、账单与支持。", ["多门店", "同步", "客户门户"], "管理层与连锁门店", "分店、许可证、销售与支持"]
      ]
    },
    advantagesPage: {
      kicker: "优势",
      title: "本地系统的速度。",
      accent: "云端管理的视野。",
      body: "混合架构让门店保持快速响应，同时为管理层提供中央视图。",
      headers: ["能力", "传统 POS", "纯云端", "TPV ERP"],
      rows: [
        ["断网时门店运营", "受限", "依赖网络", "支持本地运营"],
        ["多门店中央视图", "不常见", "包含", "同步后统一查看"],
        ["销售、管理与仓库", "工具分散", "取决于供应商", "一个互联生态"],
        ["仓库设备", "需要额外集成", "取决于供应商", "集成 PDA 应用"],
        ["运营数据控制", "仅本地", "仅云端", "本地与中央"],
        ["数据备份", "手动处理", "取决于套餐", "自动且集中管理"],
        ["新增门店", "独立配置", "新增订阅", "模块化且可扩展"],
        ["用户与权限", "基础控制", "取决于供应商", "集成角色与权限"],
        ["税务与追溯", "外部更新", "取决于服务", "支持 VeriFactu"]
      ],
      note: "这是按技术方式进行的一般比较。具体能力取决于配置及所选模块。"
    },
    contact: {
      kicker: "联系我们的团队",
      title: "您最想先改善业务的",
      accent: "哪个部分？",
      body: "选择感兴趣的解决方案并告诉我们您的需求，我们会准备贴合实际运营的演示。",
      groupLabel: "选择您感兴趣的应用",
      trustLabel: "我们如何准备演示",
      trust: [["无任何承诺", "轻松了解，无压力。"], ["定制演示", "围绕您的业务目标。"], ["贴心响应", "全程陪伴。"]],
      formTag: "申请演示",
      formTitle: "请介绍一下您的业务",
      formBody: "我们将展示 TPV ERP 如何帮助您实现目标。",
      product: "感兴趣的产品",
      name: "姓名",
      namePlaceholder: "您的姓名",
      company: "公司",
      companyPlaceholder: "公司名称",
      email: "电子邮箱",
      phone: "电话",
      caseLabel: "简要说明您的需求",
      casePlaceholder: "门店数量、行业以及希望改善的方面…",
      submit: "申请演示",
      talk: "联系团队",
      prepared: "申请内容已在您的邮件应用中准备好。",
      pendingRecipient: "最终联系渠道配置完成后，将添加销售收件人。"
    }
  }
} as const;

export function getMarketingContent(language: MarketingLanguage) {
  return content[language];
}
