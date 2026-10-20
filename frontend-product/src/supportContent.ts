import type { MarketingLanguage } from "./marketingI18n";

export type SupportContent = {
  title: string;
  summary: string;
  intro: string;
  channelsTitle: string;
  wechatLabel: string;
  wechatBody: string;
  saasBody: string;
  sections: readonly { title: string; items: readonly string[] }[];
  receiptTitle: string;
  receiptBody: string;
  unavailableBody: string;
  wechatAction: string;
  contactAction: string;
};

const content: Record<MarketingLanguage, SupportContent> = {
  es: {
    title: "Contacto y acompañamiento",
    summary: "Dónde consultar y qué alcance de soporte, formación y recuperación conviene acordar.",
    intro: "Antes de implantar esPOS, confirma qué acompañamiento se ofrece para tu configuración. Esta información orienta la consulta; no define servicios incluidos ni plazos de respuesta.",
    channelsTitle: "Dónde contactar",
    wechatLabel: "Cuenta de contacto publicada",
    wechatBody: "Escríbenos por WeChat para consultar sobre las aplicaciones o explicar qué necesitas.",
    saasBody: "Desde esPOS SaaS, los usuarios autorizados pueden consultar las opciones de soporte habilitadas para su organización, según sus permisos y la configuración del servicio.",
    sections: [
      {
        title: "Cómo explicar una incidencia",
        items: [
          "Indica la aplicación y versión, el equipo o dispositivo y el identificador de la tienda, si lo conoces.",
          "Describe qué estabas haciendo, cuándo ocurrió y el mensaje de error. Si adjuntas una captura, oculta los datos sensibles.",
          "No envíes contraseñas, códigos de acceso, datos de tarjetas ni información personal de clientes."
        ]
      },
      {
        title: "Qué hay que acordar",
        items: [
          "El alcance del soporte y los canales y horarios disponibles.",
          "Las expectativas de respuesta, cómo comunicar la prioridad y el procedimiento para tratar una incidencia.",
          "Si se ofrece ayuda remota o formación, confirma para quién, su alcance y si están incluidas."
        ]
      },
      {
        title: "Copias y recuperación",
        items: [
          "Define quién se responsabiliza de las copias y con qué frecuencia se realizan.",
          "Confirma qué datos cubren, dónde se guardan, quién puede acceder y durante cuánto tiempo se conservan.",
          "Acuerda cómo se comprueba una restauración y quién interviene para recuperar el servicio. Consulta si se incluye una copia gestionada y quién la realiza."
        ]
      }
    ],
    receiptTitle: "Después de solicitar una demo",
    receiptBody: "Solo la confirmación con una referencia de recepción acredita que la solicitud se ha registrado. No significa que se haya enviado un correo, reservado una cita ni acordado un plazo de respuesta.",
    unavailableBody: "Si el formulario no está disponible, puedes contactar por WeChat. Abrir el QR o copiar el ID no envía los datos del formulario: tendrás que escribir tu mensaje en WeChat.",
    wechatAction: "Ver contacto de WeChat",
    contactAction: "Ver formas de contacto"
  },
  en: {
    title: "Contact and assistance",
    summary: "Where to ask and what to agree about support, training and recovery.",
    intro: "Before implementing esPOS, confirm the assistance offered for your configuration. This information helps you prepare your enquiry; it does not define included services or response times.",
    channelsTitle: "Where to contact us",
    wechatLabel: "Published contact account",
    wechatBody: "Contact us on WeChat with questions about the applications or to explain what you need.",
    saasBody: "In esPOS SaaS, authorised users can view the support options enabled for their organisation, according to their permissions and service configuration.",
    sections: [
      {
        title: "How to describe an issue",
        items: [
          "Include the application and version, computer or device and store identifier, if known.",
          "Describe what you were doing, when it happened and the error message. Hide sensitive information in any screenshot you share.",
          "Do not send passwords, access codes, card details or customers' personal information."
        ]
      },
      {
        title: "What needs to be agreed",
        items: [
          "The scope of support and the available channels and operating hours.",
          "Response expectations, how to communicate priority and the procedure for handling an issue.",
          "If remote help or training is offered, confirm who it covers, its scope and whether it is included."
        ]
      },
      {
        title: "Backups and recovery",
        items: [
          "Define who is responsible for backups and how often they are made.",
          "Confirm which data is covered, where it is stored, who can access it and how long it is retained.",
          "Agree how a restore is tested and who is involved in recovering the service. Ask whether managed backups are included and who performs them."
        ]
      }
    ],
    receiptTitle: "After requesting a demo",
    receiptBody: "Only a confirmation with a receipt reference establishes that your request was registered. It does not mean an email was sent, an appointment was booked or a response deadline was agreed.",
    unavailableBody: "If the form is unavailable, you can contact us on WeChat. Opening the QR code or copying the ID does not send your form data: you need to write your message in WeChat.",
    wechatAction: "View WeChat contact",
    contactAction: "View contact options"
  },
  zh: {
    title: "联系与协助",
    summary: "了解咨询渠道，以及需确认的支持、培训和恢复服务范围。",
    intro: "部署 esPOS 前，请确认适用于您所用配置的协助内容。以下信息用于帮助您准备咨询，不代表已包含任何服务，也不承诺响应时限。",
    channelsTitle: "通过哪里联系我们",
    wechatLabel: "已公布的联系账号",
    wechatBody: "您可以通过微信咨询各应用，或说明您的具体需求。",
    saasBody: "在 esPOS SaaS 中，授权用户可根据权限和服务配置，查看所属组织已启用的支持选项。",
    sections: [
      {
        title: "如何说明问题",
        items: [
          "请说明应用名称及版本、电脑或设备，以及门店标识（如已知）。",
          "描述当时的操作、发生时间和报错信息。如需提供截图，请先遮盖敏感信息。",
          "请勿发送密码、验证码、银行卡资料或客户个人信息。"
        ]
      },
      {
        title: "需要事先确认什么",
        items: [
          "支持服务的范围、可用渠道及服务时间。",
          "响应安排、问题优先程度的说明方式及处理流程。",
          "如提供远程协助或培训，请确认对象、范围及是否已包含。"
        ]
      },
      {
        title: "备份与恢复",
        items: [
          "明确由谁负责备份，以及备份频率。",
          "确认备份涵盖哪些数据、保存位置、访问权限和保留期限。",
          "约定如何验证恢复操作，以及恢复服务时各方的职责。请咨询是否包含托管备份，以及由谁执行。"
        ]
      }
    ],
    receiptTitle: "提交演示申请后",
    receiptBody: "只有显示接收编号的确认信息，才表明申请已登记。这不代表已发送邮件、已预约时间或已约定回复期限。",
    unavailableBody: "如果表单不可用，您可以通过微信联系。打开二维码或复制微信号不会发送表单内容；您需要自行在微信中发送消息。",
    wechatAction: "查看微信联系方式",
    contactAction: "查看联系方式"
  }
};

export function getSupportContent(language: MarketingLanguage): SupportContent {
  return content[language];
}
