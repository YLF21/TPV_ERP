export type MarketingLanguage = "es" | "en" | "zh";

const content = {
  es: {
    language: "Idioma",
    navigation: ["Inicio", "Solución", "Apps", "Ventajas", "Contacto"],
    common: {
      download: "Centro de descargas",
      demo: "Solicitar demostración",
      seeApps: "Ver aplicaciones",
      compareOptions: "Comparar opciones",
      demoHint: "Demo adaptada a tu negocio y sin compromiso",
      applications: "Aplicaciones",
      advantages: "Ventajas",
      footer: "Venta, gestión y almacén conectados para comercios que no paran."
    },
    home: {
      kicker: "Software de gestión para comercios",
      title: "Vende sin fricción.",
      titleAccent: "Controla todo tu negocio.",
      lead: "TPV, gestión y almacén trabajando como uno.",
      body: "Reduce tareas duplicadas, consulta las existencias registradas y reúne la información sincronizada de una o varias tiendas.",
      checks: ["Venta local en tienda", "Crecimiento modular", "Control y trazabilidad"],
      benefits: [
        ["Venta local", "La caja necesita acceso al servicio de la tienda."],
        ["Stock bajo control", "Existencias y movimientos conectados por tienda."],
        ["Decisiones con contexto", "Documentos y datos de stock recibidos de cada tienda."],
        ["Acompañamiento a tu medida", "Consulta el alcance de implantación y soporte para tu negocio."]
      ],
      introKicker: "Del mostrador a la dirección",
      introTitle: "La información de tienda, conectada.",
      introAccent: "Menos trabajo duplicado.",
      introBody: "La tienda trabaja con su servicio local. Ventas, stock y actividad llegan a la visión central mediante sincronización; la nube y los servicios externos necesitan internet.",
      introLink: "Descubre cómo se conecta"
    },
    solution: {
      kicker: "Una operativa conectada",
      title: "Tu tienda trabaja local.",
      accent: "Tu negocio se ve completo.",
      body: "Conecta mostrador, oficina, almacén y dirección para reducir tareas manuales sin renunciar a la rapidez que necesita cada tienda.",
      principles: [
        ["Rapidez donde vendes", "Venta, caja y operación diaria responden desde la instalación de tienda."],
        ["Información que viaja contigo", "Ventas, stock y estado operativo se sincronizan con el panel central."],
        ["Control para crecer", "Añade tiendas, terminales, usuarios y dispositivos manteniendo una única visión."]
      ],
      bandKicker: "Un ecosistema, no piezas sueltas",
      bandTitle: "Del mostrador a la dirección, con información conectada.",
      bandBody: "Cada equipo utiliza su herramienta. La visión central refleja lo recibido por sincronización, no una actualización instantánea garantizada.",
      bandAction: "Conocer las aplicaciones"
    },
    features: {
      kicker: "Funcionalidades",
      title: "Menos tareas manuales.",
      accent: "Más tiempo para vender.",
      body: "Cobra, controla existencias y conoce a tus clientes con aplicaciones conectadas al servicio de tienda y a la sincronización configurada.",
      groups: [
        ["Venta y caja", "Cobra rápido sin perder el control", "Crea tickets, facturas o albaranes, combina los medios habilitados y gestiona devoluciones y cierres de caja con trazabilidad.", ["Saldo pendiente con cliente y autorización", "Integración de pagos según configuración", "Impresión y consulta de documentos"]],
        ["Catálogo y clientes", "La información correcta en cada venta", "Centraliza productos, tarifas, promociones, clientes, socios y proveedores para evitar tareas repetidas y decisiones a ciegas.", ["Precios, ofertas y fidelización", "Importación y exportación Excel", "Historial documental por cliente"]],
        ["Almacén y stock", "Existencias registradas, tienda por tienda", "Gestiona movimientos y recepciones en Gestión; usa la PDA para contar, comprobar documentos confirmados y reponer.", ["Múltiples almacenes", "Conteos y comprobaciones", "Movimientos registrados"]],
        ["Fiscalidad y seguridad", "Preparado para operar con confianza", "El sistema cuenta con permisos, auditoría y flujos fiscales. La configuración de copias, responsables y recuperación se confirma en la implantación.", ["Flujos para VeriFactu", "Usuarios y permisos", "Trazabilidad de operaciones"]]
      ]
    },
    apps: {
      kicker: "Aplicaciones esPOS",
      title: "Cada equipo, su herramienta.",
      accent: "Todo el negocio, conectado.",
      body: "Cada equipo dispone de una interfaz para sus tareas: venta y administración en tienda, movilidad en almacén y consulta central de los datos recibidos.",
      detailLabels: ["Ideal para", "Trabaja con"],
      items: [
        ["esPOS Venta", "Tu punto de venta", "Cobros, caja, tickets, facturas, albaranes y devoluciones; saldo pendiente con cliente y autorización.", ["Efectivo, tarjeta y transferencia", "Atajos de teclado", "Operativa local"], "Mostradores con venta rápida", "Tickets, caja, facturación y devoluciones"],
        ["esPOS Gestión", "El control de tu negocio", "Productos, clientes, proveedores, promociones, almacenes e informes conectados con cada venta.", ["Stock e informes", "Importación Excel", "Roles y permisos"], "Gerencia y administración", "Catálogo, compras, clientes e informes"],
        ["esPOS PDA", "Movilidad en almacén", "Inventarios, comprobación de documentos confirmados, reposición y consulta de producto desde dispositivos Android autorizados.", ["Inventarios", "Comprobaciones", "Reposición"], "Equipos de almacén", "Recuentos, documentos y transferencias"],
        ["esPOS SaaS", "Tu negocio en la nube", "Portal cliente para consultar documentos y datos de stock recibidos de tus tiendas, licencias, facturas del servicio y soporte, según permisos.", ["Multi-tienda", "Sincronización", "Portal cliente"], "Dirección y redes de tiendas", "Tiendas autorizadas, documentos y servicio"]
      ]
    },
    downloads: {
      kicker: "Aplicaciones y entrega",
      title: "Instala solo lo que necesita cada equipo",
      body: "Consulta cómo se entrega cada aplicación y accede al instalador cuando haya un enlace de descarga publicado.",
      close: "Cerrar centro de descargas",
      platform: "Plataforma",
      delivery: "Modalidad de entrega",
      available: "Descarga disponible",
      assisted: "Entrega asistida",
      downloadNow: "Descargar instalador",
      requirementsLabel: "Requisitos de instalación",
      requirements: [
        ["esPOS Venta", "Windows 64 bits, puesto compatible y acceso al servicio de la tienda configurado."],
        ["esPOS Gestión", "Windows 64 bits, acceso al servicio de tienda y permisos de administración configurados."]
      ],
      publishedLabel: "Datos de publicación",
      checksumHelp: "Compara el SHA-256 publicado con el archivo descargado para comprobar que está completo y no se ha modificado.",
      safety: "Comprueba la firma y el SHA-256 publicado antes de instalar un archivo descargado.",
      items: [
        ["Windows 64 bits", "Instalador de escritorio", "esPOS Venta se instala en el puesto de caja y se configura para la operativa de la tienda."],
        ["Windows 64 bits", "Instalador de escritorio", "esPOS Gestión se instala en los equipos de administración vinculados al negocio."],
        ["Navegador compatible en Android", "Acceso configurado durante la implantación", "esPOS PDA necesita un dispositivo autorizado, red y acceso al servicio de tienda; no dispone de APK público."],
        ["Navegador web", "Acceso habilitado para clientes", "esPOS SaaS se entrega como servicio web configurado para cada organización; no requiere instalador."]
      ]
    },
    tour: {
      kicker: "Recorrido de producto",
      title: "Conoce cada aplicación, paso a paso",
      body: "Explora tres capacidades clave y descubre qué equipo utiliza cada herramienta.",
      close: "Cerrar recorrido",
      step: "Paso",
      previous: "Anterior",
      next: "Siguiente",
      requestDemo: "Solicitar demo",
      actionsLabel: "Qué puedes hacer",
      exampleLabel: "En la práctica",
      benefitLabel: "Qué te aporta",
      deliveryLabel: "Cómo se utiliza",
    },
    advantagesPage: {
      kicker: "Ventajas",
      title: "Opera con tu servicio de tienda.",
      accent: "Decide con visión central.",
      body: "Venta y Gestión necesitan acceso al servicio de tienda. SaaS permite consultar la información recibida por sincronización, con internet y los permisos correspondientes.",
      headers: ["Capacidad", "Enfoque local", "Enfoque cloud", "esPOS"],
      rows: [
        ["Conectividad en tienda", "Según instalación y funciones", "Según servicio y modo offline", "Servicio de tienda; nube con conexión"],
        ["Visión central multi-tienda", "Requiere conexión central", "Según servicio contratado", "Con SaaS y sincronización"],
        ["Venta, gestión y almacén", "Según módulos e integraciones", "Según módulos e integraciones", "Aplicaciones conectadas según configuración"],
        ["Dispositivos de almacén", "Según compatibilidad", "Según compatibilidad", "PDA vinculada a la instalación"],
        ["Control del dato operativo", "Según arquitectura", "Según arquitectura", "Operación local y consulta central"],
        ["Copias de seguridad", "Según política de respaldo", "Según política del servicio", "Configuración revisada en la implantación"],
        ["Crecimiento a nuevas tiendas", "Según licencias y configuración", "Según plan y configuración", "Módulos según necesidades"],
        ["Usuarios y permisos", "Según producto", "Según producto", "Roles y permisos configurables"],
        ["Fiscalidad y trazabilidad", "Según producto y configuración", "Según servicio y configuración", "Flujos fiscales según implantación"]
      ],
      note: "Orientación por arquitectura, no una evaluación de proveedores concretos. Otros productos pueden combinar estos enfoques. La sincronización y los servicios externos requieren internet; alcance y módulos se confirman en cada implantación."
    },
    growth: {
      proof: {
        title: "Capacidades claras antes de tomar una decisión",
        metrics: [
          ["4", "aplicaciones conectadas"],
          ["3", "idiomas disponibles"],
          ["Local + nube", "servicio de tienda y consulta central"]
        ]
      },
      sectors: {
        title: "Una base común, adaptada a cada forma de vender",
        items: [
          ["retail", "Comercio minorista", "Vende con agilidad y conserva el control", "Conecta mostrador, catálogo, clientes y caja para que cada operación deje la información preparada para la gestión.", ["Cobros y devoluciones ágiles", "Promociones y fidelización", "Cierre de caja trazable"]],
          ["grocery", "Alimentación", "Existencias registradas para coordinar el día a día", "Coordina venta, recepción, inventarios y reposición entre tienda y almacén con una visión compartida.", ["Consulta rápida de producto", "Inventarios desde PDA", "Existencias por tienda y almacén"]],
          ["fashion", "Moda y complementos", "Catálogo, temporadas y clientes en una sola operativa", "Organiza referencias, precios y promociones mientras mantienes disponible el historial comercial de cada cliente.", ["Catálogo centralizado", "Tarifas y promociones", "Historial de cliente"]],
          ["franchise", "Cadenas y franquicias", "Autonomía en tienda, visión para toda la red", "Cada establecimiento mantiene su ritmo local mientras dirección consulta actividad, existencias y licencias desde un punto central.", ["Visión multi-tienda", "Roles y permisos", "Crecimiento modular"]]
        ]
      },
    },
    contact: {
      kicker: "Contacta con nosotros por WeChat",
      title: "Cuéntanos cómo trabajas.",
      accent: "Te enseñamos cómo mejorarlo.",
      body: "Consulta el encaje de esPOS y prepara una demo para tu negocio. Elige WeChat o el formulario.",
      wechat: {
        title: "Hablemos por WeChat",
        body: "Pregunta por aplicaciones, equipos y puesta en marcha.",
        accountLabel: "Cuenta",
        idLabel: "ID de WeChat",
        copy: "Copiar ID",
        copied: "ID copiado",
        copyError: "No se ha podido copiar. Selecciona el ID y cópialo manualmente.",
        hint: "Escanea el código QR con WeChat o copia el ID para buscarnos.",
        qrAlt: "Código QR de WeChat de esPOS - LEAFY TECH",
        viewQr: "Ver QR ampliado",
        qrTitle: "Añádenos en WeChat",
        qrHint: "Escanea este código con WeChat para añadir esPOS - LEAFY TECH a tus contactos.",
        closeQr: "Cerrar código QR"
      },
      groupLabel: "Selecciona las aplicaciones que te interesan",
      trustLabel: "Cómo preparamos la demostración",
      trust: [["Sin compromiso", "Explora sin presiones."], ["Demo adaptada", "A tu negocio y objetivos."], ["Contacto directo", "Consulta tus dudas por WeChat."]],
      formTag: "Solicita tu demo",
      formTitle: "Cuéntanos un poco más sobre tu negocio",
      formBody: "Indica tus aplicaciones de interés y las tareas que quieres mejorar.",
      product: "Aplicaciones para tu demo",
      fullPackage: "Me interesa el conjunto completo",
      fullPackageHint: "Selecciona Venta, Gestión, PDA y SaaS para la demo.",
      selectionHint: "La selección expresa tu interés en una demo; no supone una compra. Módulos, licencias, dispositivos y condiciones se acuerdan para tu negocio.",
      selectionRequired: "Selecciona al menos una aplicación o marca tu interés en el conjunto completo.",
      name: "Nombre y apellidos",
      namePlaceholder: "Tu nombre",
      company: "Empresa",
      companyPlaceholder: "Nombre de tu empresa",
      email: "Correo electrónico",
      phone: "Teléfono",
      caseLabel: "Cuéntanos brevemente tu caso",
      casePlaceholder: "Número de tiendas, sector y qué te gustaría mejorar…",
      submit: "Quiero una demo",
      submitting: "Enviando solicitud…",
      success: "Solicitud registrada. Conserva la referencia para consultar su seguimiento con nuestro equipo.",
      delivery: {
        checking: "Comprobando el servicio de solicitudes…",
        available: "El formulario está disponible para enviar tu solicitud.",
        unavailable: "El formulario no está disponible en este momento. Puedes contactar por WeChat.",
        retryAvailability: "Comprobar disponibilidad",
        retrySubmit: "Reintentar solicitud",
        viaWechat: "Contactar por WeChat",
        receiptLabel: "Referencia de la solicitud",
        fieldRequired: "Completa este campo.",
        emailInvalid: "Introduce un correo electrónico válido.",
        tooLong: "Máximo {max} caracteres.",
        validation: "Revisa los campos indicados. Tus datos siguen en el formulario.",
        conflict: "La referencia del envío no coincide con esta solicitud. Revisa los datos y vuelve a intentarlo.",
        rateLimited: "Se han realizado demasiados intentos. Espera antes de reintentar o contacta por WeChat.",
        timeout: "No hemos podido confirmar la recepción a tiempo. Reintenta con los mismos datos para evitar duplicados.",
        network: "No hemos podido confirmar la recepción. Revisa la conexión y reintenta con los mismos datos, o contacta por WeChat.",
        unavailableError: "El servicio no puede atender la solicitud ahora. Tus datos siguen en este formulario para que puedas reintentar.",
        invalidReceipt: "El servicio no ha devuelto una confirmación válida. No podemos confirmar la recepción; reintenta con los mismos datos o consulta por WeChat."
      },
      consent: "Acepto que esPOS use estos datos para responder a mi solicitud.",
      dataUse: "Usaremos tus datos únicamente para atender esta solicitud comercial.",
      faqAction: "Resolver dudas antes de solicitar la demo",
      faqClose: "Cerrar preguntas frecuentes",
      faqLabel: "Antes de decidir",
      faqTitle: "Dudas habituales, respuestas claras",
      faqHint: "Selecciona una pregunta para conocer cómo encaja esPOS en tu operativa.",
      faqItems: [
        ["¿La tienda puede seguir vendiendo sin internet?", "Venta necesita acceso al servicio de la tienda, aunque se instale en el mismo ordenador o en la red local. Si ese servicio no responde, la operativa se bloquea y se ofrece recuperar el trabajo pendiente tras reconectar. La sincronización central y los servicios externos necesitan su conexión; el alcance ante un corte de internet se valida con la configuración de tu tienda."],
        ["¿Cómo se consulta un negocio con varias tiendas?", "esPOS SaaS permite consultar documentos y datos de stock recibidos de las tiendas autorizadas, además de licencias, facturas del servicio y soporte según permisos. Necesita internet y tiendas vinculadas. Una tienda desconectada puede mostrar datos anteriores; no implica edición remota completa ni contabilidad consolidada."],
        ["¿El sistema está preparado para VeriFactu?", "Incluye flujos para VeriFactu, permisos, auditoría y trazabilidad. La configuración fiscal y el alcance se revisan para cada implantación."],
        ["¿Puedo trasladar datos desde Excel?", "esPOS Gestión permite importar y exportar información mediante Excel. El alcance de la migración se revisa antes de la puesta en marcha."],
        ["¿Qué soporte tendré durante la implantación?", "Consulta el acompañamiento disponible y acuerda las tareas, los canales y los horarios para tu implantación. Formación, ayuda remota, copias y expectativas de respuesta se confirman para tu negocio; solicitar una demo no contrata esos servicios."],
        ["¿Qué aplicación necesita cada equipo?", "Venta cubre la caja; Gestión, la administración; PDA, recuentos y comprobaciones en almacén; y SaaS, consulta de datos recibidos y del servicio. La combinación se confirma según tareas, equipos y conexión."]
      ]
    }
  },
  en: {
    language: "Language",
    navigation: ["Home", "Solution", "Apps", "Benefits", "Contact"],
    common: {
      download: "Download centre",
      demo: "Request a demo",
      seeApps: "Explore the apps",
      compareOptions: "Compare options",
      demoHint: "A tailored, no-obligation demo for your business",
      applications: "Applications",
      advantages: "Benefits",
      footer: "Connected sales, management and inventory for businesses that never stop."
    },
    home: {
      kicker: "Retail management software",
      title: "Sell without friction.",
      titleAccent: "Control your whole business.",
      lead: "POS, management and inventory working as one.",
      body: "Reduce duplicate work, check recorded stock and bring together synchronised information from one or several stores.",
      checks: ["Local in-store sales", "Modular growth", "Control and traceability"],
      benefits: [
        ["Local sales", "Checkout needs access to the store service."],
        ["Stock under control", "Inventory and movements connected by store."],
        ["Decisions with context", "Documents and stock data received from each store."],
        ["Assistance to suit your business", "Discuss the scope of rollout and support for your business."]
      ],
      introKicker: "From the counter to management",
      introTitle: "Store information, connected.",
      introAccent: "Less duplicate work.",
      introBody: "The store works with its local service. Sales, stock and activity reach the central view through synchronisation; cloud and external services need internet access.",
      introLink: "See how everything connects"
    },
    solution: {
      kicker: "A connected operation",
      title: "Your store works locally.",
      accent: "Your business stays visible.",
      body: "Connect the counter, office, warehouse and management to reduce manual work without sacrificing in-store speed.",
      principles: [
        ["Local where it matters", "Sales, checkout and daily operations respond from the store installation."],
        ["Connected when you need it", "Sales, stock and operational status sync with the central dashboard."],
        ["Ready to grow", "Add stores, terminals, users and devices while staying in control."]
      ],
      bandKicker: "One ecosystem, not separate pieces",
      bandTitle: "Connected information, from the counter to management.",
      bandBody: "Each team uses its own tool. The central view reflects information received through synchronisation, not guaranteed instant updates.",
      bandAction: "Explore the applications"
    },
    features: {
      kicker: "Features",
      title: "Less manual work.",
      accent: "More time to sell.",
      body: "Take payments, check stock and understand customers through apps connected to the store service and configured synchronisation.",
      groups: [
        ["Sales and checkout", "Take payments quickly and stay in control", "Create receipts, invoices or delivery notes, combine enabled payment methods, and manage returns and cash closing with traceability.", ["Outstanding amounts with a customer and authorisation", "Payment integration according to setup", "Document printing and lookup"]],
        ["Catalogue and customers", "The right information in every sale", "Centralise products, pricing, promotions, customers, members and suppliers to avoid repetition and blind decisions.", ["Pricing, offers and loyalty", "Excel import and export", "Document history by customer"]],
        ["Warehouse and stock", "Recorded stock, store by store", "Manage movements and goods receipts in Management; use PDA to count, check confirmed documents and replenish.", ["Multiple warehouses", "Counts and checks", "Recorded movements"]],
        ["Tax and security", "Ready to operate with confidence", "The system provides permissions, auditing and tax workflows. Backup configuration, responsibilities and recovery are confirmed during setup.", ["VeriFactu workflows", "Users and permissions", "Operation traceability"]]
      ]
    },
    apps: {
      kicker: "esPOS applications",
      title: "The right tool for every team.",
      accent: "One connected business.",
      body: "Each team has an interface for its tasks: in-store sales and administration, mobile warehouse work and a central view of received data.",
      detailLabels: ["Best for", "Works with"],
      items: [
        ["esPOS Sales", "Your point of sale", "Payments, checkout, receipts, invoices, delivery notes and returns; outstanding amounts need a customer and authorisation.", ["Cash, card and bank transfer", "Keyboard shortcuts", "Local operation"], "Fast-paced sales counters", "Receipts, checkout, billing and returns"],
        ["esPOS Management", "Control your business", "Products, customers, suppliers, promotions, warehouses and reports connected to every sale.", ["Stock and reports", "Excel import", "Roles and permissions"], "Managers and administration", "Catalogue, purchasing, customers and reports"],
        ["esPOS PDA", "Mobile warehouse work", "Inventory counts, confirmed-document checks, replenishment and product lookup from authorised Android devices.", ["Inventory", "Checks", "Replenishment"], "Warehouse teams", "Counts, documents and transfers"],
        ["esPOS SaaS", "Your business in the cloud", "A customer portal to view documents and stock data received from your stores, licences, service invoices and support, according to permissions.", ["Multi-store", "Synchronisation", "Customer portal"], "Management and store networks", "Authorised stores, documents and service"]
      ]
    },
    downloads: {
      kicker: "Apps and delivery",
      title: "Install only what each team needs",
      body: "See how each app is delivered and access the installer when a download link has been published.",
      close: "Close download centre",
      platform: "Platform",
      delivery: "Delivery method",
      available: "Download available",
      assisted: "Assisted delivery",
      downloadNow: "Download installer",
      requirementsLabel: "Installation requirements",
      requirements: [
        ["esPOS Sales", "64-bit Windows, a compatible terminal and access to the configured store service."],
        ["esPOS Management", "64-bit Windows, store service access and configured administration permissions."]
      ],
      publishedLabel: "Release information",
      checksumHelp: "Compare the published SHA-256 with the downloaded file to confirm it is complete and has not been modified.",
      safety: "Check the signature and published SHA-256 before installing a downloaded file.",
      items: [
        ["Windows 64-bit", "Desktop installer", "esPOS Sales is installed at checkout and configured for the store's operation."],
        ["Windows 64-bit", "Desktop installer", "esPOS Management is installed on administration computers connected to the business."],
        ["Compatible browser on Android", "Access configured during rollout", "esPOS PDA needs an authorised device and network access to the store service; there is no public APK."],
        ["Web browser", "Access enabled for customers", "esPOS SaaS is delivered as a web service configured for each organisation; no installer is required."]
      ]
    },
    tour: {
      kicker: "Product tour",
      title: "Explore every app, step by step",
      body: "Review three key capabilities and see which team uses each tool.",
      close: "Close tour",
      step: "Step",
      previous: "Previous",
      next: "Next",
      requestDemo: "Request a demo",
      actionsLabel: "What you can do",
      exampleLabel: "In practice",
      benefitLabel: "How it helps",
      deliveryLabel: "How to use it",
    },
    advantagesPage: {
      kicker: "Benefits",
      title: "Work with your store service.",
      accent: "Decide with a central view.",
      body: "Sales and Management need access to the store service. SaaS lets you view information received through synchronisation, with internet access and the appropriate permissions.",
      headers: ["Capability", "Local approach", "Cloud approach", "esPOS"],
      rows: [
        ["In-store connectivity", "Depends on installation and features", "Depends on service and offline mode", "Store service; cloud needs a connection"],
        ["Central multi-store view", "Requires a central connection", "Depends on the service", "With SaaS and synchronisation"],
        ["Sales, management and warehouse", "Depends on modules and integrations", "Depends on modules and integrations", "Connected apps according to configuration"],
        ["Warehouse devices", "Depends on compatibility", "Depends on compatibility", "PDA linked to the installation"],
        ["Operational data control", "Depends on architecture", "Depends on architecture", "Local operation and central view"],
        ["Backups", "Depends on backup policy", "Depends on service policy", "Configuration reviewed during setup"],
        ["Adding new stores", "Depends on licensing and setup", "Depends on plan and setup", "Modules to suit needs"],
        ["Users and permissions", "Depends on the product", "Depends on the product", "Configurable roles and permissions"],
        ["Tax and traceability", "Depends on product and setup", "Depends on service and setup", "Tax workflows according to setup"]
      ],
      note: "Architecture guidance, not an assessment of specific providers. Other products may combine these approaches. Synchronisation and external services require internet; scope and modules are confirmed for each implementation."
    },
    growth: {
      proof: {
        title: "Clear capabilities before you make a decision",
        metrics: [
          ["4", "connected applications"],
          ["3", "available languages"],
          ["Local + cloud", "store service and central view"]
        ]
      },
      sectors: {
        title: "One shared foundation, adapted to the way you sell",
        items: [
          ["retail", "Retail", "Sell quickly and stay in control", "Connect the counter, catalogue, customers and checkout so every operation leaves information ready for management.", ["Fast payments and returns", "Promotions and loyalty", "Traceable cash closing"]],
          ["grocery", "Grocery", "Recorded stock to coordinate daily work", "Coordinate sales, receiving, counts and replenishment across store and warehouse with one shared view.", ["Fast product lookup", "PDA inventory counts", "Stock by store and warehouse"]],
          ["fashion", "Fashion and accessories", "Catalogue, seasons and customers in one operation", "Organise references, prices and promotions while keeping each customer's commercial history available.", ["Centralised catalogue", "Pricing and promotions", "Customer history"]],
          ["franchise", "Chains and franchises", "Store autonomy with visibility across the network", "Each location keeps its local pace while management reviews activity, stock and licences from one central point.", ["Multi-store view", "Roles and permissions", "Modular growth"]]
        ]
      },
    },
    contact: {
      kicker: "Contact us on WeChat",
      title: "Tell us how you work.",
      accent: "We will show you how to improve it.",
      body: "Discuss whether esPOS fits your business and prepare a demo. Choose WeChat or the form.",
      wechat: {
        title: "Let's talk on WeChat",
        body: "Ask about applications, equipment and getting started.",
        accountLabel: "Account",
        idLabel: "WeChat ID",
        copy: "Copy ID",
        copied: "ID copied",
        copyError: "The ID could not be copied. Select it and copy it manually.",
        hint: "Scan the QR code with WeChat or copy the ID to find us.",
        qrAlt: "WeChat QR code for esPOS - LEAFY TECH",
        viewQr: "View larger QR code",
        qrTitle: "Add us on WeChat",
        qrHint: "Scan this code with WeChat to add esPOS - LEAFY TECH to your contacts.",
        closeQr: "Close QR code"
      },
      groupLabel: "Select the applications you are interested in",
      trustLabel: "How we prepare your demo",
      trust: [["No obligation", "Explore without pressure."], ["Tailored demo", "Built around your goals."], ["Direct contact", "Ask your questions on WeChat."]],
      formTag: "Request your demo",
      formTitle: "Tell us a little more about your business",
      formBody: "Indicate the apps you are interested in and the tasks you want to improve.",
      product: "Applications for your demo",
      fullPackage: "I'm interested in all four apps",
      fullPackageHint: "Select Sales, Management, PDA and SaaS for the demo.",
      selectionHint: "Your selection expresses interest in a demo, not a purchase. Modules, licences, devices and terms are agreed for your business.",
      selectionRequired: "Select at least one application or indicate interest in all four.",
      name: "Full name",
      namePlaceholder: "Your name",
      company: "Company",
      companyPlaceholder: "Company name",
      email: "Email address",
      phone: "Phone",
      caseLabel: "Briefly tell us about your needs",
      casePlaceholder: "Number of stores, industry and what you would like to improve…",
      submit: "Request my demo",
      submitting: "Sending request…",
      success: "Request registered. Keep the reference to ask our team about its progress.",
      delivery: {
        checking: "Checking the request service…",
        available: "The form is available to send your request.",
        unavailable: "The form is currently unavailable. You can contact us on WeChat.",
        retryAvailability: "Check availability",
        retrySubmit: "Retry request",
        viaWechat: "Contact on WeChat",
        receiptLabel: "Request reference",
        fieldRequired: "Complete this field.",
        emailInvalid: "Enter a valid email address.",
        tooLong: "Maximum {max} characters.",
        validation: "Check the highlighted fields. Your details remain in the form.",
        conflict: "The submission reference does not match this request. Review your details and try again.",
        rateLimited: "Too many attempts. Wait before retrying or contact us on WeChat.",
        timeout: "We could not confirm receipt in time. Retry with the same details to avoid duplicates.",
        network: "We could not confirm receipt. Check your connection and retry with the same details, or contact us on WeChat.",
        unavailableError: "The service cannot process the request right now. Your details remain in this form so you can retry.",
        invalidReceipt: "The service did not return a valid confirmation. We cannot confirm receipt; retry with the same details or ask us on WeChat."
      },
      consent: "I agree that esPOS may use these details to respond to my request.",
      dataUse: "We will use your details only to handle this sales request.",
      faqAction: "Answer questions before requesting a demo",
      faqClose: "Close frequently asked questions",
      faqLabel: "Before you decide",
      faqTitle: "Common questions, clear answers",
      faqHint: "Choose a question to see how esPOS fits your operation.",
      faqItems: [
        ["Can the store keep selling without internet?", "Sales needs access to the store service, even when it runs on the same computer or local network. If that service is unavailable, operations are blocked and pending work can be recovered after reconnecting. Central synchronisation and external services need their connection; the scope during an internet outage is checked against your store configuration."],
        ["How can I view multiple stores?", "esPOS SaaS lets you view documents and stock data received from authorised stores, plus licences, service invoices and support within your permissions. It needs internet access and linked stores. A disconnected store may show older data; this does not imply full remote editing or consolidated accounting."],
        ["Is the system ready for VeriFactu?", "It includes VeriFactu workflows, permissions, auditing and traceability. Tax configuration and scope are reviewed for each implementation."],
        ["Can I move data from Excel?", "esPOS Management supports importing and exporting information with Excel. The migration scope is reviewed before go-live."],
        ["What support is available during rollout?", "Discuss the available assistance and agree tasks, channels and operating hours for your rollout. Training, remote help, backups and response expectations are confirmed for your business; requesting a demo does not purchase those services."],
        ["Which app does each team need?", "Sales covers checkout; Management, administration; PDA, warehouse counts and checks; and SaaS, received data and service information. The combination is confirmed against tasks, equipment and connectivity."]
      ]
    }
  },
  zh: {
    language: "语言",
    navigation: ["首页", "解决方案", "应用", "优势", "联系"],
    common: {
      download: "下载中心",
      demo: "申请演示",
      seeApps: "查看应用",
      compareOptions: "比较方案",
      demoHint: "根据您的业务定制，无需承诺",
      applications: "应用",
      advantages: "优势",
      footer: "连接销售、管理与库存，为持续运营的零售业务而生。"
    },
    home: {
      kicker: "零售业务管理软件",
      title: "销售顺畅无阻。",
      titleAccent: "全面掌控业务。",
      lead: "收银、管理与库存协同工作。",
      body: "减少重复工作，查询已登记库存，并集中查看一家或多家门店同步收到的信息。",
      checks: ["门店本地销售", "模块化扩展", "操作记录与追溯"],
      benefits: [
        ["本地销售", "收银需要访问门店服务。"],
        ["库存尽在掌控", "各门店库存与流转信息保持连接。"],
        ["决策更有依据", "查看从各门店接收到的单据与库存数据。"],
        ["按需确认协助范围", "根据业务情况咨询实施与支持服务范围。"]
      ],
      introKicker: "从柜台到管理层",
      introTitle: "门店信息相互连接。",
      introAccent: "减少重复处理。",
      introBody: "门店连接本地服务工作。销售、库存与业务活动通过同步传至中央视图；云端和外部服务需要互联网连接。",
      introLink: "了解如何互联"
    },
    solution: {
      kicker: "互联的业务运营",
      title: "门店本地高效运行。",
      accent: "全局业务清晰可见。",
      body: "连接收银台、办公室、仓库与管理层，在保持门店速度的同时减少手工作业。",
      principles: [
        ["本地关键操作", "销售、收银和日常运营由门店本地系统快速响应。"],
        ["按需连接", "销售、库存与运营状态同步至中央面板。"],
        ["为增长而准备", "增加门店、终端、用户和设备，同时保持掌控。"]
      ],
      bandKicker: "一个生态，而非分散工具",
      bandTitle: "从柜台到管理层，信息相互连接。",
      bandBody: "每个团队使用对应工具。中央视图反映实际同步收到的信息，并不保证所有数据即时更新。",
      bandAction: "了解应用"
    },
    features: {
      kicker: "功能",
      title: "减少手工作业。",
      accent: "留出更多销售时间。",
      body: "通过连接门店服务及已配置同步机制的应用完成收款、库存查询与客户管理。",
      groups: [
        ["销售与收银", "快速收款，操作可查", "创建小票、发票或送货单，组合已启用的付款方式，并可追溯地管理退货与结账。", ["指定客户并获授权后保留待收金额", "按配置集成支付方式", "单据打印与查询"]],
        ["商品与客户", "每次销售都有正确信息", "集中管理商品、价格、促销、客户、会员和供应商，减少重复工作。", ["价格、优惠与会员", "Excel 导入导出", "客户单据历史"]],
        ["仓库与库存", "逐店查看已登记库存", "在管理端处理库存流转与收货；通过 PDA 盘点、核查已确认单据及补货。", ["多仓库", "盘点与核查", "库存流转记录"]],
        ["税务与安全", "安心运营", "系统提供权限、审计与税务流程。备份配置、负责人及恢复安排需在实施时确认。", ["VeriFactu 相关流程", "用户与权限", "操作可追溯"]]
      ]
    },
    apps: {
      kicker: "esPOS 应用",
      title: "每个团队都有合适工具。",
      accent: "整个业务保持互联。",
      body: "各团队使用对应界面：门店销售与管理、移动仓库作业，以及集中查询已接收的数据。",
      detailLabels: ["适合", "覆盖业务"],
      items: [
        ["esPOS 销售", "您的销售终端", "处理收银、小票、发票、送货单与退货；保留待收金额需指定客户并取得授权。", ["现金、刷卡与转账", "键盘快捷键", "本地运行"], "高频销售柜台", "小票、收银、开票与退货"],
        ["esPOS 管理", "掌控业务", "商品、客户、供应商、促销、仓库与报表连接每一笔销售。", ["库存与报表", "Excel 导入", "角色与权限"], "管理层与行政人员", "商品、采购、客户与报表"],
        ["esPOS PDA", "移动仓库作业", "通过已授权的 Android 设备盘点、核查已确认单据、补货与查询商品。", ["盘点", "核查", "补货"], "仓库团队", "盘点、单据与调拨"],
        ["esPOS SaaS", "云端业务", "客户门户可按权限查看从门店接收到的单据与库存数据，以及许可证、服务账单和支持。", ["多门店", "同步", "客户门户"], "管理层与连锁门店", "已授权门店、单据与服务"]
      ]
    },
    downloads: {
      kicker: "应用与交付",
      title: "为每个团队安装所需工具",
      body: "查看每款应用的交付方式，并在下载链接已公布时获取安装程序。",
      close: "关闭下载中心",
      platform: "平台",
      delivery: "交付方式",
      available: "可下载",
      assisted: "协助交付",
      downloadNow: "下载安装程序",
      requirementsLabel: "安装要求",
      requirements: [
        ["esPOS 销售", "64 位 Windows、兼容终端，以及连接已配置门店服务的条件。"],
        ["esPOS 管理", "64 位 Windows、连接门店服务的条件，以及已配置的管理权限。"]
      ],
      publishedLabel: "发布信息",
      checksumHelp: "将公布的 SHA-256 与下载文件进行比较，以确认文件完整且未被修改。",
      safety: "安装下载的文件前，请检查签名及已公布的 SHA-256。",
      items: [
        ["Windows 64 位", "桌面安装程序", "esPOS 销售安装在收银终端，并根据门店运营进行配置。"],
        ["Windows 64 位", "桌面安装程序", "esPOS 管理安装在与业务相连的管理电脑上。"],
        ["Android 兼容浏览器", "实施期间配置访问", "esPOS PDA 需要授权设备通过网络访问门店服务，不提供公开 APK。"],
        ["网页浏览器", "为客户开通访问", "esPOS SaaS 作为按组织配置的网页服务交付，无需安装程序。"]
      ]
    },
    tour: {
      kicker: "产品导览",
      title: "逐步了解每款应用",
      body: "查看三项关键能力，并了解每款工具适合哪个团队。",
      close: "关闭导览",
      step: "步骤",
      previous: "上一步",
      next: "下一步",
      requestDemo: "申请演示",
      actionsLabel: "可以做什么",
      exampleLabel: "实际场景",
      benefitLabel: "带来的帮助",
      deliveryLabel: "使用方式",
    },
    advantagesPage: {
      kicker: "优势",
      title: "连接门店服务开展作业。",
      accent: "管理层集中决策。",
      body: "销售和管理应用需要访问门店服务。SaaS 通过互联网按权限展示实际同步收到的信息。",
      headers: ["能力", "本地架构", "云端架构", "esPOS"],
      rows: [
        ["门店连接要求", "取决于安装方式和功能", "取决于服务和离线模式", "需连接门店服务；云端需联网"],
        ["多门店中央视图", "需要中央连接", "取决于所选服务", "通过 SaaS 和同步实现"],
        ["销售、管理与仓库", "取决于模块和集成", "取决于模块和集成", "按配置连接各应用"],
        ["仓库设备", "取决于兼容性", "取决于兼容性", "PDA 与安装环境绑定"],
        ["运营数据控制", "取决于架构", "取决于架构", "本地操作与中央查看"],
        ["数据备份", "取决于备份策略", "取决于服务策略", "实施时确认备份配置"],
        ["新增门店", "取决于许可和配置", "取决于套餐和配置", "按需配置模块"],
        ["用户与权限", "取决于产品", "取决于产品", "可配置角色与权限"],
        ["税务与追溯", "取决于产品和配置", "取决于服务和配置", "按实施配置税务流程"]
      ],
      note: "这是架构选择说明，并非对具体供应商的评估；其他产品也可能结合这些方式。同步和外部服务需要联网，具体范围与模块需在实施时确认。"
    },
    growth: {
      proof: {
        title: "做出决定前，先清楚了解产品能力",
        metrics: [
          ["4", "款互联应用"],
          ["3", "种可用语言"],
          ["本地 + 云端", "门店服务与中央查询"]
        ]
      },
      sectors: {
        title: "共享同一基础，适配不同销售方式",
        items: [
          ["retail", "零售门店", "快速销售，同时保持全面掌控", "连接柜台、商品目录、客户与收银，让每次操作都为后续管理准备好准确信息。", ["快速收款与退货", "促销与会员管理", "可追溯的收银结算"]],
          ["grocery", "食品零售", "用已登记库存信息协调日常业务", "通过共享视图协调门店与仓库之间的销售、收货、盘点和补货。", ["快速查询商品", "通过 PDA 盘点", "按门店和仓库查看库存"]],
          ["fashion", "服装与配饰", "统一管理商品、季节和客户", "集中整理商品款号、价格和促销，同时保留每位客户的交易历史。", ["集中商品目录", "价格与促销", "客户历史记录"]],
          ["franchise", "连锁与加盟", "门店自主运营，总部掌握全局", "各门店保持本地运营节奏，管理层则从中央视图查看业务、库存和许可证。", ["多门店视图", "角色与权限", "模块化扩展"]]
        ]
      },
    },
    contact: {
      kicker: "通过微信联系我们",
      title: "告诉我们您的工作方式。",
      accent: "我们展示如何改善。",
      body: "咨询 esPOS 是否适合您的业务，并准备产品演示。您可以选择微信或表单联系。",
      wechat: {
        title: "微信聊一聊",
        body: "咨询应用、设备及上线准备方面的问题。",
        accountLabel: "账号",
        idLabel: "微信号",
        copy: "复制微信号",
        copied: "微信号已复制",
        copyError: "复制失败，请选中微信号并手动复制。",
        hint: "使用微信扫描二维码，或复制微信号搜索我们。",
        qrAlt: "esPOS - LEAFY TECH 的微信二维码",
        viewQr: "查看大图二维码",
        qrTitle: "添加我们的微信",
        qrHint: "使用微信扫描此二维码，将 esPOS - LEAFY TECH 添加到您的联系人。",
        closeQr: "关闭二维码"
      },
      groupLabel: "选择一个或多个感兴趣的应用",
      trustLabel: "我们如何准备演示",
      trust: [["无购买义务", "轻松了解，无压力。"], ["定制演示", "围绕您的业务目标。"], ["直接联系", "通过微信咨询问题。"]],
      formTag: "申请演示",
      formTitle: "请介绍一下您的业务",
      formBody: "选择感兴趣的应用，并说明希望改善的工作。",
      product: "希望演示的应用",
      fullPackage: "我对全部四款应用感兴趣",
      fullPackageHint: "为演示选择销售、管理、PDA 和 SaaS 四款应用。",
      selectionHint: "所选应用仅表示演示意向，不代表购买。功能模块、许可、设备及相关条件将根据您的业务需求另行商定。",
      selectionRequired: "请至少选择一款应用，或勾选对全部四款应用的演示意向。",
      name: "姓名",
      namePlaceholder: "您的姓名",
      company: "公司",
      companyPlaceholder: "公司名称",
      email: "电子邮箱",
      phone: "电话",
      caseLabel: "简要说明您的需求",
      casePlaceholder: "门店数量、行业以及希望改善的方面…",
      submit: "申请演示",
      submitting: "正在发送申请…",
      success: "申请已登记。请保留参考编号，以便向团队查询处理进度。",
      delivery: {
        checking: "正在检查申请服务…",
        available: "表单可用，您可以发送申请。",
        unavailable: "表单目前不可用。您可以通过微信联系我们。",
        retryAvailability: "检查服务状态",
        retrySubmit: "重试申请",
        viaWechat: "通过微信联系",
        receiptLabel: "申请参考编号",
        fieldRequired: "请填写此项。",
        emailInvalid: "请输入有效的电子邮箱。",
        tooLong: "最多 {max} 个字符。",
        validation: "请检查标记的字段。您填写的信息仍保留在表单中。",
        conflict: "发送参考编号与此申请不一致。请检查信息后重试。",
        rateLimited: "尝试次数过多。请稍后重试，或通过微信联系。",
        timeout: "未能及时确认是否收到申请。请使用相同信息重试以避免重复登记。",
        network: "无法确认是否收到申请。请检查网络并使用相同信息重试，或通过微信联系。",
        unavailableError: "服务暂时无法处理申请。您填写的信息仍保留在此表单中，方便重试。",
        invalidReceipt: "服务未返回有效确认，无法确认是否已收到。请使用相同信息重试，或通过微信咨询。"
      },
      consent: "我同意 esPOS 使用这些信息回复我的申请。",
      dataUse: "我们只会使用这些信息处理本次商业咨询。",
      faqAction: "申请演示前查看常见问题",
      faqClose: "关闭常见问题",
      faqLabel: "决策之前",
      faqTitle: "常见问题，清晰解答",
      faqHint: "选择一个问题，了解 esPOS 如何适配您的业务流程。",
      faqItems: [
        ["互联网中断时门店还能继续销售吗？", "销售需要连接门店服务，即使该服务安装在同一台电脑或局域网中。如果服务无法响应，操作会被阻止，重新连接后可恢复待处理工作。中央同步和外部服务需要相应连接；互联网中断时的可用范围需结合门店实际配置确认。"],
        ["如何查询多家门店？", "esPOS SaaS 可按权限查询从授权门店接收到的单据与库存数据，以及许可证、服务账单和支持。需要互联网连接及已关联门店。门店断网时可能显示较早数据，并不代表可完整远程编辑或进行合并会计处理。"],
        ["系统是否已为 VeriFactu 做好准备？", "提供 VeriFactu 相关流程、权限、审计及操作追踪。具体税务配置及适用范围需按每次实施确认。"],
        ["可以从 Excel 迁移数据吗？", "esPOS 管理支持通过 Excel 导入和导出信息。上线前会确认迁移范围，以便正确整理数据。"],
        ["实施期间可以获得哪些支持？", "请咨询可提供的协助，并商定实施任务、沟通渠道和服务时间。培训、远程协助、备份和响应安排需按业务情况确认；申请演示并不代表订购这些服务。"],
        ["每个团队需要哪款应用？", "销售用于收银，管理用于行政管理，PDA 用于仓库盘点与核查，SaaS 用于查询已接收数据和服务信息。具体组合需根据任务、设备和连接条件确认。"]
      ]
    }
  }
} as const;

export function getMarketingContent(language: MarketingLanguage) {
  return content[language];
}
