export type MarketingLanguage = "es" | "en" | "zh";

const content = {
  es: {
    language: "Idioma",
    navigation: ["Inicio", "Solución", "Funcionalidades", "Apps", "Ventajas", "Contacto"],
    common: {
      download: "Centro de descargas",
      demo: "Solicitar demostración",
      exploreSolution: "Ver cómo funciona",
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
      body: "Reduce tareas duplicadas, conoce el stock real y dirige una o varias tiendas con una visión central.",
      checks: ["Operativa local sin interrupciones", "Crecimiento modular", "Control y trazabilidad"],
      benefits: [
        ["Venta sin interrupciones", "La caja sigue respondiendo en el día a día."],
        ["Stock bajo control", "Existencias y movimientos conectados por tienda."],
        ["Decisiones con contexto", "Ventas e informes reunidos en una visión central."],
        ["Equipo acompañado", "Soporte para implantar, operar y crecer."]
      ],
      introKicker: "Del mostrador a la dirección",
      introTitle: "Cada venta actualiza el negocio.",
      introAccent: "Sin repetir el trabajo.",
      introBody: "La tienda mantiene la rapidez de una operación local mientras ventas, stock y actividad se sincronizan para ofrecerte una visión completa.",
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
      bandTitle: "Una venta mueve stock, cliente e informes al mismo tiempo.",
      bandBody: "Cada equipo trabaja con la herramienta adecuada, pero todos comparten la misma información operativa.",
      bandAction: "Conocer las aplicaciones"
    },
    features: {
      kicker: "Funcionalidades",
      title: "Menos tareas manuales.",
      accent: "Más tiempo para vender.",
      body: "Cobra, controla existencias, conoce a tus clientes y protege cada operación desde una base de datos compartida.",
      groups: [
        ["Venta y caja", "Cobra rápido sin perder el control", "Crea tickets, facturas o albaranes, combina formas de pago y gestiona devoluciones y cierres de caja con trazabilidad.", ["Ventas pendientes y cobros posteriores", "Integración con terminales de pago", "Impresión y recuperación segura"]],
        ["Catálogo y clientes", "La información correcta en cada venta", "Centraliza productos, tarifas, promociones, clientes, socios y proveedores para evitar tareas repetidas y decisiones a ciegas.", ["Precios, ofertas y fidelización", "Importación y exportación Excel", "Historial documental por cliente"]],
        ["Almacén y stock", "Existencias reales, tienda por tienda", "Controla movimientos, recepciones, inventarios y reposición desde Gestión o desde la PDA de almacén.", ["Múltiples almacenes", "Conteos y comprobaciones", "Stock sincronizado"]],
        ["Fiscalidad y seguridad", "Preparado para operar con confianza", "Permisos, auditoría, copias de seguridad y flujos fiscales forman parte del sistema desde su diseño.", ["Preparado para VeriFactu", "Usuarios y permisos", "Trazabilidad de operaciones"]]
      ]
    },
    apps: {
      kicker: "Aplicaciones TPV ERP",
      title: "Cada equipo, su herramienta.",
      accent: "Todo el negocio, conectado.",
      body: "Mostrador, administración, almacén y dirección trabajan con interfaces claras sin perder una única fuente de información.",
      detailLabels: ["Ideal para", "Trabaja con"],
      items: [
        ["APP Venta", "Tu punto de venta", "Cobros ágiles, caja, tickets, facturas, devoluciones y ventas pendientes en una interfaz preparada para el mostrador.", ["Efectivo, tarjeta y transferencia", "Atajos de teclado", "Operativa local"], "Mostradores con venta rápida", "Tickets, caja, facturación y devoluciones"],
        ["APP Gestión", "El control de tu negocio", "Productos, clientes, proveedores, promociones, almacenes e informes conectados con cada venta.", ["Stock e informes", "Importación Excel", "Roles y permisos"], "Gerencia y administración", "Catálogo, compras, clientes e informes"],
        ["APP PDA", "Movilidad en almacén", "Recepciones, inventarios, reposición y consulta de producto desde dispositivos vinculados de forma segura.", ["Inventarios", "Comprobaciones", "Reposición"], "Equipos de almacén", "Recepción, inventario, ubicación y reposición"],
        ["APP SaaS", "Tu negocio en la nube", "Visión central de tiendas, licencias, ventas, existencias, facturación y soporte desde cualquier lugar.", ["Multi-tienda", "Sincronización", "Portal cliente"], "Dirección y redes de tiendas", "Sucursales, licencias, ventas y soporte"]
      ]
    },
    downloads: {
      kicker: "Aplicaciones y entrega",
      title: "Instala solo lo que necesita cada equipo",
      body: "Consulta cómo se entrega cada aplicación. Los enlaces directos solo aparecen cuando existe un instalador publicado y verificado.",
      close: "Cerrar centro de descargas",
      platform: "Plataforma",
      delivery: "Modalidad de entrega",
      version: "Versión publicada",
      available: "Descarga disponible",
      assisted: "Entrega asistida",
      downloadNow: "Descargar instalador",
      requestSetup: "Solicitar instalación",
      requirementsLabel: "Requisitos de instalación",
      requirements: [
        ["APP Venta", "Windows 64 bits, un puesto de caja compatible y configuración inicial de la tienda."],
        ["APP Gestión", "Windows 64 bits, acceso autorizado al negocio y conexión para la sincronización."]
      ],
      publishedLabel: "Publicación verificada",
      checksumHelp: "Compara el SHA-256 publicado con el archivo descargado para comprobar que está completo y no se ha modificado.",
      safety: "Las aplicaciones de escritorio se publican únicamente con firma y comprobación SHA-256.",
      items: [
        ["Windows 64 bits", "Instalador de escritorio", "APP Venta se instala en el puesto de caja y se configura para la operativa de la tienda."],
        ["Windows 64 bits", "Instalador de escritorio", "APP Gestión se instala en los equipos de administración vinculados al negocio."],
        ["Navegador en dispositivo móvil", "Acceso configurado durante la implantación", "APP PDA funciona como aplicación web vinculada a la instalación; no dispone de APK público."],
        ["Navegador web", "Acceso habilitado para clientes", "APP SaaS se entrega como servicio web configurado para cada organización; no requiere instalador."]
      ]
    },
    tour: {
      kicker: "Recorrido de producto",
      title: "Conoce cada aplicación, paso a paso",
      body: "Explora tres capacidades clave y descubre qué equipo utiliza cada herramienta.",
      viewTour: "Ver recorrido",
      close: "Cerrar recorrido",
      chooseApp: "Selecciona una aplicación",
      step: "Paso",
      progress: "Pasos del recorrido",
      previous: "Anterior",
      next: "Siguiente",
      requestDemo: "Solicitar demo",
      actionsLabel: "Qué puedes hacer",
      exampleLabel: "En la práctica",
      benefitLabel: "Qué te aporta",
      deliveryLabel: "Cómo se utiliza",
      previewNote: "Interfaz real · Datos de demostración",
      previewAlts: [
        "APP Venta: ticket con productos y total de venta de demostración",
        "APP Gestión: panel de ventas e informes con datos de demostración",
        "APP PDA: herramientas de almacén en un dispositivo móvil de demostración",
        "APP SaaS: panel central de tiendas con datos de demostración"
      ],
      stepBodies: [
        ["Combina las formas de pago habituales dentro del flujo de venta.", "Usa accesos rápidos para mantener el ritmo en el mostrador.", "La caja conserva su capacidad de trabajo local durante la operativa diaria."],
        ["Consulta existencias e informes desde el entorno de administración.", "Importa y exporta información con Excel para reducir tareas repetidas.", "Organiza el acceso mediante usuarios, roles y permisos."],
        ["Prepara y registra inventarios desde el dispositivo vinculado.", "Comprueba cantidades y movimientos mientras trabajas en el almacén.", "Consulta productos y apoya las tareas de reposición desde el dispositivo."],
        ["Reúne la visión de varias tiendas desde un entorno central.", "Mantiene conectada la información operativa entre tienda y gestión central.", "Ofrece un espacio web para licencias, actividad y soporte del cliente."]
      ],
      stepDetails: [
        [
          {
            actions: [
              "Registra cobros en efectivo desde el ticket de venta.",
              "Selecciona los medios de pago habilitados en la tienda.",
              "Revisa el importe pendiente antes de cerrar el cobro."
            ],
            example: "Prepara un ticket, recibe el pago en efectivo y comprueba el saldo antes de finalizar.",
            benefit: "Mantén el cobro y el saldo pendiente asociados a la venta."
          },
          {
            actions: [
              "Abre las deudas del cliente seleccionado con Ctrl+D.",
              "Consulta el stock del artículo seleccionado con F5.",
              "Accede al cobro con los atajos de la pantalla de venta."
            ],
            example: "Con un cliente seleccionado, Ctrl+D abre sus deudas; F5 permite consultar el stock del artículo seleccionado.",
            benefit: "Resuelve consultas de cliente y producto desde el teclado."
          },
          {
            actions: [
              "Prepara el ticket desde la instalación del puesto de caja.",
              "Consulta el catálogo disponible en el equipo de caja.",
              "Revisa las ventas registradas en la operativa local."
            ],
            example: "La venta se prepara en el puesto de caja; la sincronización y los servicios externos necesitan su conexión.",
            benefit: "Apoya el trabajo diario en la instalación de la tienda."
          }
        ],
        [
          {
            actions: [
              "Consulta el stock por artículo y almacén desde Gestión.",
              "Revisa los movimientos para entender cambios de existencias.",
              "Consulta informes de ventas para analizar la actividad."
            ],
            example: "Antes de preparar una compra, revisa las existencias y los movimientos del artículo junto con la actividad de ventas.",
            benefit: "Relaciona ventas y existencias para decidir con más contexto."
          },
          {
            actions: [
              "Prepara las columnas de la hoja antes de importar productos.",
              "Carga el archivo Excel desde las herramientas de Gestión.",
              "Exporta datos para revisarlos y compartirlos con el equipo."
            ],
            example: "Prepara una hoja de productos, revisa su estructura e impórtala al catálogo desde Gestión.",
            benefit: "Reduce la transcripción manual al trabajar con hojas de cálculo."
          },
          {
            actions: [
              "Asigna a cada usuario un rol acorde con su trabajo.",
              "Configura los permisos de las funciones de administración.",
              "Revisa qué operaciones puede realizar cada perfil."
            ],
            example: "El equipo de caja y la administración utilizan perfiles distintos, con permisos adaptados a sus tareas.",
            benefit: "Ajusta el acceso a las responsabilidades de cada equipo."
          }
        ],
        [
          {
            actions: [
              "Selecciona el almacén en el dispositivo vinculado.",
              "Escanea artículos y registra las cantidades contadas.",
              "Revisa diferencias antes de confirmar el inventario."
            ],
            example: "Al contar una estantería, escanea artículos y anota cantidades; revisa las diferencias antes de aplicar el inventario.",
            benefit: "Contrasta el recuento con las existencias antes de actualizarlas."
          },
          {
            actions: [
              "Abre un documento de almacén que ya esté confirmado.",
              "Contrasta sus cantidades con lo que encuentras físicamente.",
              "Revisa las diferencias antes de cerrar la comprobación."
            ],
            example: "Tras una recepción confirmada, compara el documento con la mercancía: la comprobación no registra una nueva entrada de stock.",
            benefit: "Detecta discrepancias sin duplicar la entrada del documento."
          },
          {
            actions: [
              "Consulta el stock del artículo en cada almacén.",
              "Selecciona almacenes de origen y destino para reponer.",
              "Registra la cantidad que transfieres entre los almacenes."
            ],
            example: "Para reponer el almacén de tienda, consulta el stock disponible y prepara una transferencia desde el almacén de reserva.",
            benefit: "Reubica existencias con un movimiento registrado entre almacenes."
          }
        ],
        [
          {
            actions: [
              "Consulta documentos de las tiendas vinculadas al negocio.",
              "Identifica a qué tienda corresponde cada operación.",
              "Selecciona una tienda para consultar sus existencias."
            ],
            example: "Dirección consulta documentos de distintas tiendas y selecciona una para revisar su stock, sin mezclar las existencias.",
            benefit: "Revisa la actividad de la red y conserva el contexto de cada tienda."
          },
          {
            actions: [
              "Selecciona la tienda cuyo estado quieres consultar.",
              "Revisa su stock y el estado de sincronización disponible.",
              "Comprueba la última información recibida de esa tienda."
            ],
            example: "Al revisar una tienda, el panel muestra el stock recibido y su estado de sincronización; la actualización depende de la conexión.",
            benefit: "Consulta el estado de la tienda con el contexto de su sincronización."
          },
          {
            actions: [
              "Accede al portal con la cuenta autorizada del negocio.",
              "Consulta las licencias vinculadas a tu organización.",
              "Consulta facturas del servicio SaaS y opciones de soporte."
            ],
            example: "Con el acceso habilitado durante la configuración, el cliente consulta sus licencias y las facturas del servicio SaaS.",
            benefit: "Reúne la información del servicio en el portal de tu organización."
          }
        ]
      ]
    },
    advantagesPage: {
      kicker: "Ventajas",
      title: "Sigue vendiendo en local.",
      accent: "Decide con visión central.",
      body: "Combina continuidad operativa en tienda con control multi-tienda, sincronización y acceso central para la dirección.",
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
    growth: {
      proof: {
        kicker: "Un ecosistema preparado para tu operativa",
        title: "Capacidades claras antes de tomar una decisión",
        metrics: [
          ["4", "aplicaciones conectadas"],
          ["3", "idiomas disponibles"],
          ["Local + nube", "continuidad y visión central"]
        ],
        testimonialQuote: "Escenario ilustrativo: el equipo de caja vende con rapidez, almacén actualiza existencias y dirección consulta el negocio sin pedir los mismos datos dos veces.",
        testimonialAuthor: "Ejemplo de uso de TPV ERP",
        testimonialRole: "Escenario orientativo, no testimonio de un cliente real"
      },
      sectors: {
        kicker: "Pensado para tu sector",
        title: "Una base común, adaptada a cada forma de vender",
        body: "Elige un tipo de negocio para ver cómo TPV ERP conecta las tareas que más importan en su día a día.",
        selectorLabel: "Selecciona tu tipo de negocio",
        items: [
          ["retail", "Comercio minorista", "Vende con agilidad y conserva el control", "Conecta mostrador, catálogo, clientes y caja para que cada operación deje la información preparada para la gestión.", ["Cobros y devoluciones ágiles", "Promociones y fidelización", "Cierre de caja trazable"]],
          ["grocery", "Alimentación", "Stock actualizado para un negocio que no se detiene", "Coordina venta, recepción, inventarios y reposición entre tienda y almacén con una visión compartida.", ["Consulta rápida de producto", "Inventarios desde PDA", "Existencias por tienda y almacén"]],
          ["fashion", "Moda y complementos", "Catálogo, temporadas y clientes en una sola operativa", "Organiza referencias, precios y promociones mientras mantienes disponible el historial comercial de cada cliente.", ["Catálogo centralizado", "Tarifas y promociones", "Historial de cliente"]],
          ["franchise", "Cadenas y franquicias", "Autonomía en tienda, visión para toda la red", "Cada establecimiento mantiene su ritmo local mientras dirección consulta actividad, existencias y licencias desde un punto central.", ["Visión multi-tienda", "Roles y permisos", "Crecimiento modular"]]
        ]
      },
    },
    contact: {
      kicker: "Contacta con nuestro equipo",
      title: "Cuéntanos cómo trabajas.",
      accent: "Te enseñamos cómo mejorarlo.",
      body: "Dinos cuántas tiendas tienes, cómo vendes y qué quieres controlar mejor. Prepararemos una demostración enfocada en tu operativa real.",
      groupLabel: "Selecciona las aplicaciones que te interesan",
      trustLabel: "Cómo preparamos la demostración",
      trust: [["Sin compromiso", "Explora sin presiones."], ["Demo adaptada", "A tu negocio y objetivos."], ["Respuesta cercana", "Te acompañamos."]],
      formTag: "Solicita tu demo",
      formTitle: "Cuéntanos un poco más sobre tu negocio",
      formBody: "Te mostraremos cómo TPV ERP puede ayudarte con tus objetivos.",
      product: "Aplicaciones de interés",
      fullPackage: "Paquete completo",
      fullPackageHint: "Incluye Venta, Gestión, PDA y SaaS",
      selectionRequired: "Selecciona al menos una aplicación o el paquete completo.",
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
      success: "Solicitud recibida. Nuestro equipo se pondrá en contacto contigo.",
      error: "No hemos podido enviar la solicitud. Revisa la conexión e inténtalo de nuevo.",
      consent: "Acepto que TPV ERP use estos datos para responder a mi solicitud.",
      dataUse: "Usaremos tus datos únicamente para atender esta solicitud comercial.",
      faqAction: "Resolver dudas antes de solicitar la demo",
      faqClose: "Cerrar preguntas frecuentes",
      faqLabel: "Antes de decidir",
      faqTitle: "Dudas habituales, respuestas claras",
      faqHint: "Selecciona una pregunta para conocer cómo encaja TPV ERP en tu operativa.",
      faqItems: [
        ["¿La tienda puede seguir vendiendo sin internet?", "Sí. La venta, la caja y la operativa diaria se ejecutan en local. Cuando vuelve la conexión, la información se sincroniza con la gestión central."],
        ["¿Cómo se controla un negocio con varias tiendas?", "APP SaaS ofrece una visión central de tiendas, licencias, ventas, existencias y facturación, mientras cada tienda mantiene su operativa local."],
        ["¿El sistema está preparado para VeriFactu?", "El sistema está preparado para trabajar con flujos de VeriFactu e incluye permisos, auditoría, copias de seguridad y trazabilidad. La configuración concreta se revisa para cada implantación."],
        ["¿Puedo trasladar datos desde Excel?", "APP Gestión permite importar y exportar información mediante Excel. El alcance de la migración se revisa antes de la puesta en marcha."],
        ["¿Qué soporte tendré durante la implantación?", "El equipo acompaña la configuración, la puesta en marcha y las dudas de uso. El alcance y el canal se concretan para cada implantación."],
        ["¿Qué aplicación necesita cada equipo?", "APP Venta cubre la caja; APP Gestión, la administración; APP PDA, el almacén; y APP SaaS, la visión central y multi-tienda."]
      ]
    }
  },
  en: {
    language: "Language",
    navigation: ["Home", "Solution", "Features", "Apps", "Benefits", "Contact"],
    common: {
      download: "Download center",
      demo: "Request a demo",
      exploreSolution: "See how it works",
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
      body: "Reduce duplicate work, know your real stock and manage one or several stores from a central view.",
      checks: ["Uninterrupted local operation", "Modular growth", "Control and traceability"],
      benefits: [
        ["Uninterrupted sales", "Checkout keeps responding throughout the day."],
        ["Stock under control", "Inventory and movements connected by store."],
        ["Decisions with context", "Sales and reports together in one central view."],
        ["A supported team", "Help to implement, operate and grow."]
      ],
      introKicker: "From the counter to management",
      introTitle: "Every sale updates the business.",
      introAccent: "Without repeating the work.",
      introBody: "The store keeps the speed of local operation while sales, stock and activity sync into one complete view.",
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
      bandTitle: "What happens at checkout updates the rest of your business.",
      bandBody: "Less duplication, fewer manual tasks and shared traceability for every team.",
      bandAction: "Explore the applications"
    },
    features: {
      kicker: "Features",
      title: "Less manual work.",
      accent: "More time to sell.",
      body: "Take payments, control stock, understand customers and protect every operation from one shared data foundation.",
      groups: [
        ["Sales and checkout", "Take payments quickly and stay in control", "Create receipts, invoices or delivery notes, combine payment methods, and manage returns and cash closing with full traceability.", ["Pending sales and later payments", "Payment terminal integration", "Secure printing and recovery"]],
        ["Catalogue and customers", "The right information in every sale", "Centralise products, pricing, promotions, customers, members and suppliers to avoid repetition and blind decisions.", ["Pricing, offers and loyalty", "Excel import and export", "Document history by customer"]],
        ["Warehouse and stock", "Real stock, store by store", "Manage movements, receipts, counts and replenishment from Management or the warehouse PDA.", ["Multiple warehouses", "Counts and checks", "Synced stock"]],
        ["Tax and security", "Ready to operate with confidence", "Permissions, auditing, backups and tax workflows are built into the system from the start.", ["Ready for VeriFactu", "Users and permissions", "Operation traceability"]]
      ]
    },
    apps: {
      kicker: "TPV ERP applications",
      title: "The right tool for every team.",
      accent: "One connected business.",
      body: "Counter, administration, warehouse and management get focused interfaces while sharing one source of information.",
      detailLabels: ["Best for", "Works with"],
      items: [
        ["Sales App", "Your point of sale", "Fast payments, checkout, receipts, invoices, returns and pending sales in an interface built for the counter.", ["Cash, card and bank transfer", "Keyboard shortcuts", "Local operation"], "Fast-paced sales counters", "Receipts, checkout, billing and returns"],
        ["Management App", "Control your business", "Products, customers, suppliers, promotions, warehouses and reports connected to every sale.", ["Stock and reports", "Excel import", "Roles and permissions"], "Managers and administration", "Catalogue, purchasing, customers and reports"],
        ["PDA App", "Mobile warehouse work", "Receipts, inventory, replenishment and product lookup from securely linked devices.", ["Inventory", "Checks", "Replenishment"], "Warehouse teams", "Receiving, inventory, locations and replenishment"],
        ["SaaS App", "Your business in the cloud", "A central view of stores, licences, sales, stock, billing and support from anywhere.", ["Multi-store", "Synchronisation", "Customer portal"], "Management and store networks", "Branches, licences, sales and support"]
      ]
    },
    downloads: {
      kicker: "Apps and delivery",
      title: "Install only what each team needs",
      body: "See how every app is delivered. Direct links appear only when a verified installer has been published.",
      close: "Close download center",
      platform: "Platform",
      delivery: "Delivery method",
      version: "Published version",
      available: "Download available",
      assisted: "Assisted delivery",
      downloadNow: "Download installer",
      requestSetup: "Request installation",
      requirementsLabel: "Installation requirements",
      requirements: [
        ["Sales App", "64-bit Windows, a compatible checkout terminal and initial store configuration."],
        ["Management App", "64-bit Windows, authorized business access and a connection for synchronization."]
      ],
      publishedLabel: "Verified release",
      checksumHelp: "Compare the published SHA-256 with the downloaded file to confirm it is complete and has not been modified.",
      safety: "Desktop applications are published only after signature and SHA-256 verification.",
      items: [
        ["Windows 64-bit", "Desktop installer", "APP Sales is installed at checkout and configured for the store's operation."],
        ["Windows 64-bit", "Desktop installer", "APP Management is installed on administration computers connected to the business."],
        ["Browser on a mobile device", "Access configured during rollout", "APP PDA is a web app linked to the installation; there is no public APK."],
        ["Web browser", "Access enabled for customers", "APP SaaS is delivered as a web service configured for each organization; no installer is required."]
      ]
    },
    tour: {
      kicker: "Product tour",
      title: "Explore every app, step by step",
      body: "Review three key capabilities and see which team uses each tool.",
      viewTour: "View tour",
      close: "Close tour",
      chooseApp: "Choose an application",
      step: "Step",
      progress: "Tour steps",
      previous: "Previous",
      next: "Next",
      requestDemo: "Request a demo",
      actionsLabel: "What you can do",
      exampleLabel: "In practice",
      benefitLabel: "How it helps",
      deliveryLabel: "How to use it",
      previewNote: "Real interface · Demo data",
      previewAlts: [
        "Sales App: receipt with products and a demo sale total",
        "Management App: sales dashboard and reports with demo data",
        "PDA App: warehouse tools on a demo mobile device",
        "SaaS App: central store dashboard with demo data"
      ],
      stepBodies: [
        ["Combine common payment methods within the sales flow.", "Use quick access controls to keep checkout moving.", "Checkout keeps its local working capability during daily operations."],
        ["Review stock and reports from the administration environment.", "Import and export information with Excel to reduce repetitive work.", "Organize access with users, roles and permissions."],
        ["Prepare and record inventory counts from the linked device.", "Check quantities and movements while working in the warehouse.", "Look up products and support replenishment work from the device."],
        ["Bring multiple stores into one central view.", "Keep operational information connected between the store and central management.", "Provide a web space for customer licences, activity and support."]
      ],
      stepDetails: [
        [
          {
            actions: [
              "Record cash payments from the current sales receipt.",
              "Choose the payment methods enabled for the store.",
              "Review the outstanding amount before completing payment."
            ],
            example: "Prepare a receipt, take the cash payment and check the balance before completing the sale.",
            benefit: "Keep payments and the outstanding balance linked to the sale."
          },
          {
            actions: [
              "Open the selected customer's receivables with Ctrl+D.",
              "Check stock for the selected product by pressing F5.",
              "Open payment using the shortcuts on the sales screen."
            ],
            example: "With a customer selected, Ctrl+D opens their receivables; F5 lets you check stock for the selected product.",
            benefit: "Handle customer and product enquiries from the keyboard."
          },
          {
            actions: [
              "Prepare sales receipts on the local checkout workstation.",
              "Look up the catalogue available on the checkout computer.",
              "Review sales recorded through the local checkout operation."
            ],
            example: "Sales are prepared at the checkout workstation; synchronisation and external services need their connection.",
            benefit: "Support daily checkout work from the store's installation."
          }
        ],
        [
          {
            actions: [
              "Look up stock by product and warehouse in Management.",
              "Review movements to understand changes in stock levels.",
              "Consult sales reports to understand business activity."
            ],
            example: "Before preparing a purchase, review the product's stock and movements alongside sales activity.",
            benefit: "Connect sales and stock information for better informed decisions."
          },
          {
            actions: [
              "Prepare spreadsheet columns before importing products.",
              "Load the Excel file from the Management application's tools.",
              "Export data to review it and share it with your team."
            ],
            example: "Prepare a product spreadsheet, review its structure and import it into the catalogue from Management.",
            benefit: "Reduce manual transcription when working with spreadsheets."
          },
          {
            actions: [
              "Assign each user a role that matches their responsibilities.",
              "Configure permissions for administration functions.",
              "Review which operations each user profile can perform."
            ],
            example: "Checkout and administration use different profiles, with permissions adapted to their tasks.",
            benefit: "Match access to the responsibilities of each team."
          }
        ],
        [
          {
            actions: [
              "Select the warehouse on your linked mobile device.",
              "Scan stock items and record the quantities you count.",
              "Review differences before confirming the inventory count."
            ],
            example: "When counting a shelf, scan items and enter quantities; review differences before applying the inventory count.",
            benefit: "Compare the count with recorded stock before updating it."
          },
          {
            actions: [
              "Open a warehouse document that has already been confirmed.",
              "Compare its quantities with the goods physically present.",
              "Review any differences before completing the check."
            ],
            example: "After receiving is confirmed, compare the document with the goods: this check does not record another stock entry.",
            benefit: "Find discrepancies without duplicating the document's stock entry."
          },
          {
            actions: [
              "Look up the product's stock levels in each warehouse.",
              "Select source and destination warehouses for replenishment.",
              "Record the quantity transferred between the warehouses."
            ],
            example: "To replenish the store warehouse, check available stock and prepare a transfer from the reserve warehouse.",
            benefit: "Relocate stock with a recorded movement between warehouses."
          }
        ],
        [
          {
            actions: [
              "Review documents from stores linked to the business.",
              "Identify which store each recorded operation belongs to.",
              "Select an individual store to review its stock levels."
            ],
            example: "Management reviews documents from different stores and selects one to check its stock, keeping each store's inventory separate.",
            benefit: "Review activity across the network with each store's context."
          },
          {
            actions: [
              "Select the store whose status you want to review.",
              "Review its stock and the available synchronisation status.",
              "Check the latest information received from that store."
            ],
            example: "For the selected store, the panel shows received stock data and its synchronisation status; updates depend on the connection.",
            benefit: "Review the store's status alongside its synchronisation context."
          },
          {
            actions: [
              "Sign in to the portal with an authorised business account.",
              "Review the licences assigned to your organisation.",
              "Check SaaS service invoices and available support options."
            ],
            example: "Once access has been enabled during setup, the customer can review licences and SaaS service invoices.",
            benefit: "Keep service information together in your organisation's portal."
          }
        ]
      ]
    },
    advantagesPage: {
      kicker: "Benefits",
      title: "Keep selling locally.",
      accent: "Decide with a central view.",
      body: "Combine operational continuity in store with multi-store control, synchronisation and central access for management.",
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
    growth: {
      proof: {
        kicker: "An ecosystem built around your operation",
        title: "Clear capabilities before you make a decision",
        metrics: [
          ["4", "connected applications"],
          ["3", "available languages"],
          ["Local + cloud", "continuity and central visibility"]
        ],
        testimonialQuote: "Illustrative scenario: checkout keeps sales moving, the warehouse updates stock, and management sees the business without asking teams for the same information twice.",
        testimonialAuthor: "TPV ERP usage example",
        testimonialRole: "Illustrative scenario, not a real customer testimonial"
      },
      sectors: {
        kicker: "Designed for your industry",
        title: "One shared foundation, adapted to the way you sell",
        body: "Choose a business type to see how TPV ERP connects the tasks that matter most in its daily operation.",
        selectorLabel: "Select your business type",
        items: [
          ["retail", "Retail", "Sell quickly and stay in control", "Connect the counter, catalogue, customers and checkout so every operation leaves information ready for management.", ["Fast payments and returns", "Promotions and loyalty", "Traceable cash closing"]],
          ["grocery", "Grocery", "Up-to-date stock for a business that never stops", "Coordinate sales, receiving, counts and replenishment across store and warehouse with one shared view.", ["Fast product lookup", "PDA inventory counts", "Stock by store and warehouse"]],
          ["fashion", "Fashion and accessories", "Catalogue, seasons and customers in one operation", "Organize references, prices and promotions while keeping each customer's commercial history available.", ["Centralized catalogue", "Pricing and promotions", "Customer history"]],
          ["franchise", "Chains and franchises", "Store autonomy with visibility across the network", "Each location keeps its local pace while management reviews activity, stock and licences from one central point.", ["Multi-store view", "Roles and permissions", "Modular growth"]]
        ]
      },
    },
    contact: {
      kicker: "Talk to our team",
      title: "Tell us how you work.",
      accent: "We will show you how to improve it.",
      body: "Tell us how many stores you run, how you sell and what you want to control better. We will tailor the demo to your real operation.",
      groupLabel: "Select the applications you are interested in",
      trustLabel: "How we prepare your demo",
      trust: [["No commitment", "Explore without pressure."], ["Tailored demo", "Built around your goals."], ["Personal response", "We support you."]],
      formTag: "Request your demo",
      formTitle: "Tell us a little more about your business",
      formBody: "We will show you how TPV ERP can support your goals.",
      product: "Applications of interest",
      fullPackage: "Complete package",
      fullPackageHint: "Includes Sales, Management, PDA and SaaS",
      selectionRequired: "Select at least one application or the complete package.",
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
      success: "Request received. Our team will get in touch with you.",
      error: "We could not send your request. Check your connection and try again.",
      consent: "I agree that TPV ERP may use these details to respond to my request.",
      dataUse: "We will use your details only to handle this sales request.",
      faqAction: "Answer questions before requesting a demo",
      faqClose: "Close frequently asked questions",
      faqLabel: "Before you decide",
      faqTitle: "Common questions, clear answers",
      faqHint: "Choose a question to see how TPV ERP fits your operation.",
      faqItems: [
        ["Can the store keep selling without internet?", "Yes. Sales, checkout and daily operations run locally. When the connection returns, information synchronizes with central management."],
        ["How are multiple stores managed?", "APP SaaS provides a central view of stores, licences, sales, stock and billing, while each location keeps its local operation."],
        ["Is the system ready for VeriFactu?", "The system is prepared for VeriFactu flows and includes permissions, auditing, backups and operation traceability. The specific setup is reviewed for each deployment."],
        ["Can I move data from Excel?", "APP Management supports importing and exporting information with Excel. The migration scope is reviewed before go-live."],
        ["What support is available during rollout?", "The team assists with setup, go-live and usage questions. The support scope and channel are agreed for each implementation."],
        ["Which app does each team need?", "APP Sales covers checkout; APP Management, administration; APP PDA, warehouse work; and APP SaaS, central and multi-store visibility."]
      ]
    }
  },
  zh: {
    language: "语言",
    navigation: ["首页", "解决方案", "功能", "应用", "优势", "联系"],
    common: {
      download: "下载中心",
      demo: "申请演示",
      exploreSolution: "了解运行方式",
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
      body: "减少重复工作，掌握真实库存，并通过中央视图管理一家或多家门店。",
      checks: ["本地运营不中断", "模块化扩展", "操作全程可追溯"],
      benefits: [
        ["销售不中断", "收银台在日常运营中持续快速响应。"],
        ["库存尽在掌控", "各门店库存与流转信息保持连接。"],
        ["决策更有依据", "销售与报表汇集到中央视图。"],
        ["团队获得支持", "从实施、运营到扩展全程协助。"]
      ],
      introKicker: "从柜台到管理层",
      introTitle: "每笔销售都会更新业务。",
      introAccent: "无需重复处理。",
      introBody: "门店保持本地运营的速度，同时销售、库存与业务活动同步到完整视图。",
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
      bandTitle: "收银台发生的一切都会更新整个业务。",
      bandBody: "减少重复与手工作业，让每个团队共享完整追溯信息。",
      bandAction: "了解应用"
    },
    features: {
      kicker: "功能",
      title: "减少手工作业。",
      accent: "留出更多销售时间。",
      body: "在共享数据基础上完成收款、库存控制、客户管理并保护每项操作。",
      groups: [
        ["销售与收银", "快速收款，始终可控", "创建小票、发票或送货单，组合支付方式，并可追溯地管理退货与结账。", ["挂单与后续收款", "支付终端集成", "安全打印与恢复"]],
        ["商品与客户", "每次销售都有正确信息", "集中管理商品、价格、促销、客户、会员和供应商，减少重复工作。", ["价格、优惠与会员", "Excel 导入导出", "客户单据历史"]],
        ["仓库与库存", "门店库存真实可见", "通过管理端或仓库 PDA 管理出入库、收货、盘点与补货。", ["多仓库", "盘点与核查", "库存同步"]],
        ["税务与安全", "安心运营", "权限、审计、备份与税务流程从系统设计之初即已内置。", ["支持 VeriFactu", "用户与权限", "操作可追溯"]]
      ]
    },
    apps: {
      kicker: "TPV ERP 应用",
      title: "每个团队都有合适工具。",
      accent: "整个业务保持互联。",
      body: "柜台、行政、仓库与管理层使用专注界面，同时共享同一信息来源。",
      detailLabels: ["适合", "覆盖业务"],
      items: [
        ["销售应用", "您的销售终端", "快速收款、收银、小票、发票、退货与挂单，专为柜台操作设计。", ["现金、刷卡与转账", "键盘快捷键", "本地运行"], "高频销售柜台", "小票、收银、开票与退货"],
        ["管理应用", "掌控业务", "商品、客户、供应商、促销、仓库与报表连接每一笔销售。", ["库存与报表", "Excel 导入", "角色与权限"], "管理层与行政人员", "商品、采购、客户与报表"],
        ["PDA 应用", "移动仓库作业", "通过安全连接的设备完成收货、盘点、补货与商品查询。", ["盘点", "核查", "补货"], "仓库团队", "收货、盘点、库位与补货"],
        ["SaaS 应用", "云端业务", "随时查看门店、许可证、销售、库存、账单与支持。", ["多门店", "同步", "客户门户"], "管理层与连锁门店", "分店、许可证、销售与支持"]
      ]
    },
    downloads: {
      kicker: "应用与交付",
      title: "为每个团队安装所需工具",
      body: "查看每款应用的交付方式。只有在已发布并验证安装程序后，才会显示直接下载链接。",
      close: "关闭下载中心",
      platform: "平台",
      delivery: "交付方式",
      version: "已发布版本",
      available: "可下载",
      assisted: "协助交付",
      downloadNow: "下载安装程序",
      requestSetup: "申请安装",
      requirementsLabel: "安装要求",
      requirements: [
        ["销售应用", "64 位 Windows、兼容的收银终端，以及门店初始配置。"],
        ["管理应用", "64 位 Windows、已授权的业务访问权限，以及用于同步的网络连接。"]
      ],
      publishedLabel: "已验证发布",
      checksumHelp: "将公布的 SHA-256 与下载文件进行比较，以确认文件完整且未被修改。",
      safety: "桌面应用仅在完成签名和 SHA-256 校验后发布。",
      items: [
        ["Windows 64 位", "桌面安装程序", "APP Venta 安装在收银终端，并根据门店运营进行配置。"],
        ["Windows 64 位", "桌面安装程序", "APP Gestión 安装在与业务相连的管理电脑上。"],
        ["移动设备浏览器", "实施期间配置访问", "APP PDA 是与系统相连的网页应用，目前没有公开 APK。"],
        ["网页浏览器", "为客户开通访问", "APP SaaS 作为按组织配置的网页服务交付，无需安装程序。"]
      ]
    },
    tour: {
      kicker: "产品导览",
      title: "逐步了解每款应用",
      body: "查看三项关键能力，并了解每款工具适合哪个团队。",
      viewTour: "查看导览",
      close: "关闭导览",
      chooseApp: "选择应用",
      step: "步骤",
      progress: "导览步骤",
      previous: "上一步",
      next: "下一步",
      requestDemo: "申请演示",
      actionsLabel: "可以做什么",
      exampleLabel: "实际场景",
      benefitLabel: "带来的帮助",
      deliveryLabel: "使用方式",
      previewNote: "真实界面 · 演示数据",
      previewAlts: [
        "销售应用：包含商品与销售总额的演示小票",
        "管理应用：使用演示数据的销售与报表仪表盘",
        "PDA 应用：移动设备上的仓库工具演示",
        "SaaS 应用：使用演示数据的门店中央仪表盘"
      ],
      stepBodies: [
        ["在销售流程中组合常用付款方式。", "通过快捷操作保持收银台的工作节奏。", "收银台在日常运营中保留本地工作能力。"],
        ["从管理环境查看库存和报表。", "通过 Excel 导入和导出信息，减少重复工作。", "通过用户、角色和权限组织访问。"],
        ["在已连接设备上准备并记录库存盘点。", "在仓库作业时核对数量和移动记录。", "通过设备查询商品并支持补货任务。"],
        ["在中央视图中汇总多家门店。", "保持门店与中央管理之间的运营信息连接。", "为客户提供许可证、运营活动和支持的网页空间。"]
      ],
      stepDetails: [
        [
          {
            actions: [
              "在当前销售小票中记录现金收款。",
              "选择门店已启用的付款方式。",
              "完成收款前核对尚未支付的金额。"
            ],
            example: "准备小票，收取现金，并在完成销售前核对待付金额。",
            benefit: "将收款记录和待付金额与对应销售关联。"
          },
          {
            actions: [
              "使用 Ctrl+D 查看所选客户的欠款。",
              "按 F5 查询所选商品的库存。",
              "使用销售界面的快捷键进入收款。"
            ],
            example: "选择客户后，Ctrl+D 打开其欠款；F5 可查询当前所选商品的库存。",
            benefit: "通过键盘完成客户与商品查询。"
          },
          {
            actions: [
              "在收银终端的本地安装中准备小票。",
              "查询收银电脑上可用的商品目录。",
              "查看通过本地收银操作记录的销售。"
            ],
            example: "销售在门店收银终端处理；同步和外部服务仍需相应的网络连接。",
            benefit: "通过门店本地安装支持日常收银工作。"
          }
        ],
        [
          {
            actions: [
              "在管理端按商品和仓库查询库存。",
              "查看库存流转记录，了解数量变化。",
              "查询销售报表，了解业务活动。"
            ],
            example: "准备采购前，结合商品库存、流转记录和销售情况进行查看。",
            benefit: "结合销售与库存信息，为决策提供依据。"
          },
          {
            actions: [
              "导入商品前整理电子表格的列。",
              "通过管理端工具加载 Excel 文件。",
              "导出数据，供团队查看和共享。"
            ],
            example: "准备商品表格，检查结构后，通过管理端导入商品目录。",
            benefit: "处理电子表格时减少手动转录。"
          },
          {
            actions: [
              "根据工作职责为每位用户分配角色。",
              "配置管理功能的访问权限。",
              "查看各用户角色可以执行的操作。"
            ],
            example: "收银团队和行政人员使用不同角色，权限根据各自任务配置。",
            benefit: "让访问权限与各团队的职责相匹配。"
          }
        ],
        [
          {
            actions: [
              "在已连接的移动设备上选择仓库。",
              "扫描商品并记录实际清点的数量。",
              "确认盘点前检查库存差异。"
            ],
            example: "清点货架时扫描商品、填写数量，并在应用盘点结果前检查差异。",
            benefit: "更新库存前，将实盘数量与库存记录核对。"
          },
          {
            actions: [
              "打开已经确认的仓库单据。",
              "将单据数量与实际货物进行核对。",
              "结束核查前查看发现的差异。"
            ],
            example: "收货已确认后，将单据与货物核对；核查不会再次记录库存入库。",
            benefit: "发现数量差异，同时避免重复记录单据入库。"
          },
          {
            actions: [
              "查询商品在各仓库的库存。",
              "选择补货的来源仓库和目标仓库。",
              "记录在仓库之间调拨的数量。"
            ],
            example: "为门店仓库补货时，查看可用库存，并准备从储备仓库调拨商品。",
            benefit: "通过仓库之间的流转记录调整库存位置。"
          }
        ],
        [
          {
            actions: [
              "查看已关联门店的业务单据。",
              "识别每笔操作所属的门店。",
              "选择一家门店查看其库存。"
            ],
            example: "管理层查看不同门店的单据，再选择一家查询库存，各店库存保持区分。",
            benefit: "了解门店网络的业务活动，并保留各店背景。"
          },
          {
            actions: [
              "选择需要查看状态的门店。",
              "查看该店库存和可用的同步状态。",
              "检查最近收到的该店信息。"
            ],
            example: "选择门店后，面板显示已收到的库存数据和同步状态；更新取决于网络连接。",
            benefit: "结合同步情况查看门店状态。"
          },
          {
            actions: [
              "使用已授权的业务账号登录门户。",
              "查看分配给您组织的许可证。",
              "查看 SaaS 服务账单和可用支持选项。"
            ],
            example: "配置期间开通访问后，客户可以查看许可证以及 SaaS 服务账单。",
            benefit: "在组织门户中集中查看服务信息。"
          }
        ]
      ]
    },
    advantagesPage: {
      kicker: "优势",
      title: "门店继续本地销售。",
      accent: "管理层集中决策。",
      body: "结合门店运营连续性、多门店控制、同步能力与管理层的中央访问。",
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
    growth: {
      proof: {
        kicker: "围绕实际运营打造的生态系统",
        title: "做出决定前，先清楚了解产品能力",
        metrics: [
          ["4", "款互联应用"],
          ["3", "种可用语言"],
          ["本地 + 云端", "兼顾连续运营与集中视图"]
        ],
        testimonialQuote: "示例场景：收银团队保持销售节奏，仓库及时更新库存，管理层无需重复索取相同数据即可了解业务。",
        testimonialAuthor: "TPV ERP 使用示例",
        testimonialRole: "仅为说明性场景，并非真实客户评价"
      },
      sectors: {
        kicker: "为您的行业而设计",
        title: "共享同一基础，适配不同销售方式",
        body: "选择一种业务类型，了解 TPV ERP 如何连接日常运营中最重要的任务。",
        selectorLabel: "选择您的业务类型",
        items: [
          ["retail", "零售门店", "快速销售，同时保持全面掌控", "连接柜台、商品目录、客户与收银，让每次操作都为后续管理准备好准确信息。", ["快速收款与退货", "促销与会员管理", "可追溯的收银结算"]],
          ["grocery", "食品零售", "用实时库存支持不停运转的业务", "通过共享视图协调门店与仓库之间的销售、收货、盘点和补货。", ["快速查询商品", "通过 PDA 盘点", "按门店和仓库查看库存"]],
          ["fashion", "服装与配饰", "统一管理商品、季节和客户", "集中整理商品款号、价格和促销，同时保留每位客户的交易历史。", ["集中商品目录", "价格与促销", "客户历史记录"]],
          ["franchise", "连锁与加盟", "门店自主运营，总部掌握全局", "各门店保持本地运营节奏，管理层则从中央视图查看业务、库存和许可证。", ["多门店视图", "角色与权限", "模块化扩展"]]
        ]
      },
    },
    contact: {
      kicker: "联系我们的团队",
      title: "告诉我们您的工作方式。",
      accent: "我们展示如何改善。",
      body: "告诉我们门店数量、销售方式以及希望加强的控制，我们会根据真实运营准备演示。",
      groupLabel: "选择一个或多个感兴趣的应用",
      trustLabel: "我们如何准备演示",
      trust: [["无任何承诺", "轻松了解，无压力。"], ["定制演示", "围绕您的业务目标。"], ["贴心响应", "全程陪伴。"]],
      formTag: "申请演示",
      formTitle: "请介绍一下您的业务",
      formBody: "我们将展示 TPV ERP 如何帮助您实现目标。",
      product: "感兴趣的应用",
      fullPackage: "完整套装",
      fullPackageHint: "包含销售、管理、PDA 和 SaaS 应用",
      selectionRequired: "请至少选择一个应用或完整套装。",
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
      success: "申请已收到。我们的团队将与您联系。",
      error: "申请发送失败。请检查网络后重试。",
      consent: "我同意 TPV ERP 使用这些信息回复我的申请。",
      dataUse: "我们只会使用这些信息处理本次商业咨询。",
      faqAction: "申请演示前查看常见问题",
      faqClose: "关闭常见问题",
      faqLabel: "决策之前",
      faqTitle: "常见问题，清晰解答",
      faqHint: "选择一个问题，了解 TPV ERP 如何适配您的业务流程。",
      faqItems: [
        ["没有网络时门店还能继续销售吗？", "可以。销售、收银和日常操作在门店本地运行。网络恢复后，信息会与中央管理端同步。"],
        ["多家门店如何统一管理？", "APP SaaS 集中展示门店、许可证、销售、库存和开票信息，同时每家门店保留本地运营能力。"],
        ["系统是否已为 VeriFactu 做好准备？", "系统已为 VeriFactu 相关流程做好准备，并包含权限、审计、备份和操作追踪。具体配置会根据每次实施情况进行确认。"],
        ["可以从 Excel 迁移数据吗？", "APP Gestión 支持通过 Excel 导入和导出信息。上线前会确认迁移范围，以便正确整理数据。"],
        ["实施期间可以获得哪些支持？", "团队会协助配置、上线和日常使用问题。每次实施都会明确支持范围和沟通渠道。"],
        ["每个团队需要哪款应用？", "APP Venta 用于收银，APP Gestión 用于管理，APP PDA 用于仓库作业，APP SaaS 用于中央和多门店视图。"]
      ]
    }
  }
} as const;

export function getMarketingContent(language: MarketingLanguage) {
  return content[language];
}
