import type { MarketingLanguage } from "./marketingI18n";

export const implementationStepIds = ["scope", "review", "validation", "rollout"] as const;
export type ImplementationStepId = typeof implementationStepIds[number];

export type ImplementationStepContent = {
  title: string;
  body: string;
  customerPreparation: string;
  confirmation: string;
};

export type ImplementationJourneyContent = {
  title: string;
  intro: string;
  preparationLabel: string;
  confirmationLabel: string;
  steps: Record<ImplementationStepId, ImplementationStepContent>;
  note: string;
};

const content: Record<MarketingLanguage, ImplementationJourneyContent> = {
  es: {
    title: "Después de la demo: preparar la implantación",
    intro: "Un recorrido orientativo para acordar qué se hará, quién lo realizará y cómo se comprobará antes de empezar.",
    preparationLabel: "Qué puedes preparar",
    confirmationLabel: "Qué hay que confirmar",
    steps: {
      scope: {
        title: "Acordar el alcance y las responsabilidades",
        body: "La demostración ayuda a revisar el encaje. Si decides continuar, el siguiente paso es concretar una propuesta para tu operativa.",
        customerPreparation: "Una relación de tiendas, puestos, tareas prioritarias y una persona responsable de coordinar la revisión.",
        confirmation: "Aplicaciones, módulos, servicios y tareas incluidos o excluidos, quién realiza cada uno y qué resultado se revisará."
      },
      review: {
        title: "Revisar una muestra de datos y los equipos",
        body: "Antes de plantear una importación o reutilizar dispositivos, conviene comprobar formatos, campos, sistema, red y periféricos con el alcance previsto.",
        customerPreparation: "Una lista de modelos y versiones, y, si se acuerda, una muestra anonimizada del catálogo o de los datos a trasladar. No envíes contraseñas ni datos reales de clientes por el formulario.",
        confirmation: "Compatibilidad de cada equipo e integración, datos que pueden trasladarse, transformaciones necesarias y límites de la migración. Una muestra válida no equivale a una migración completa incluida."
      },
      validation: {
        title: "Definir la configuración y las pruebas",
        body: "Para las tareas acordadas, se prepara la configuración que corresponda y se comprueba el flujo con datos de prueba antes de autorizar su uso real.",
        customerPreparation: "Casos representativos de venta o almacén, perfiles de usuario y criterios con los que comprobar que la operativa responde a tus necesidades.",
        confirmation: "Quién configura y prueba, qué comprobaciones deben superarse y quién acepta el resultado. Los accesos, las copias de seguridad y cualquier cambio en producción requieren una preparación y autorización acordadas."
      },
      rollout: {
        title: "Preparar la formación y la puesta en marcha",
        body: "Si se acuerdan formación o acompañamiento, hay que concretar las personas, las tareas y el formato. La fecha de arranque se decide según lo validado y lo contratado.",
        customerPreparation: "Personas que utilizarán cada aplicación, disponibilidad para practicar y una persona responsable de validar que el equipo está preparado.",
        confirmation: "Qué formación y ayuda al arranque se acuerdan y con qué alcance, responsables del cambio, copias y recuperación, canal de incidencias y fechas."
      }
    },
    note: "Proceso orientativo, no una oferta cerrada de servicios. Antes del arranque se acuerdan tareas, responsables, migración, formación y fechas. Abrir esta guía o solicitar una demo no modifica datos de producción, no activa aplicaciones ni realiza una compra."
  },
  en: {
    title: "After the demo: preparing your rollout",
    intro: "An indicative journey to agree what will be done, who will do it and how it will be checked before getting started.",
    preparationLabel: "What you can prepare",
    confirmationLabel: "What needs confirmation",
    steps: {
      scope: {
        title: "Agree the scope and responsibilities",
        body: "The demo helps assess the fit. If you decide to continue, the next step is to discuss a proposal for your business.",
        customerPreparation: "A list of stores, workstations and priority tasks, plus a contact responsible for coordinating the review.",
        confirmation: "Applications, modules, services and tasks included or excluded, who performs each one and which results will be checked."
      },
      review: {
        title: "Review a data sample and your equipment",
        body: "Before considering an import or reusing devices, formats, fields, operating systems, network and peripherals should be checked against the intended scope.",
        customerPreparation: "A list of models and versions and, if agreed, an anonymised sample of the catalogue or data to transfer. Do not send passwords or real customer data through the form.",
        confirmation: "Compatibility of each device and integration, data that can be transferred, required transformations and migration limits. An accepted sample does not mean a full migration is included."
      },
      validation: {
        title: "Define the configuration and checks",
        body: "For the agreed tasks, the relevant configuration is prepared and the workflow is checked with test data before live use is authorised.",
        customerPreparation: "Representative sales or warehouse scenarios, user roles and criteria for checking that the workflow meets your needs.",
        confirmation: "Who configures and tests, which checks must pass and who accepts the result. Access, backups and any production changes require agreed preparation and authorisation."
      },
      rollout: {
        title: "Prepare training and go-live",
        body: "If training or rollout assistance is agreed, the participants, tasks and format need to be specified. The start date depends on what has been validated and contracted.",
        customerPreparation: "The people who will use each application, availability to practise and a person responsible for confirming the team is ready.",
        confirmation: "What training and go-live assistance is agreed and its scope, change ownership, backups and recovery, the incident channel and dates."
      }
    },
    note: "An indicative process, not a fixed service offer. Tasks, responsibilities, migration, training and dates are agreed before rollout. Opening this guide or requesting a demo does not change production data, activate applications or make a purchase."
  },
  zh: {
    title: "演示之后：准备系统实施",
    intro: "以下为参考流程，用于在开始前商定工作范围、负责人及验收方式。",
    preparationLabel: "您可以准备什么",
    confirmationLabel: "需要确认什么",
    steps: {
      scope: {
        title: "商定实施范围与责任分工",
        body: "演示用于评估是否适合您的业务。如决定继续，下一步是结合实际业务讨论具体方案。",
        customerPreparation: "门店、工作站和优先任务清单，以及一位负责协调评估的联系人。",
        confirmation: "包括或不包括的应用、模块、服务和任务，各项工作的负责人及验收结果。"
      },
      review: {
        title: "检查数据样本与设备",
        body: "考虑导入数据或沿用设备前，应按计划范围检查格式、字段、系统、网络和外设。",
        customerPreparation: "设备型号及版本清单；如双方商定，可准备拟迁移商品或数据的匿名化样本。请勿通过表单发送密码或真实客户数据。",
        confirmation: "各设备与集成的兼容性、可迁移的数据、所需转换及迁移边界。样本检查通过不代表已包含完整数据迁移。"
      },
      validation: {
        title: "明确配置与测试要求",
        body: "针对已商定的任务准备相应配置，并在授权正式使用前以测试数据检查业务流程。",
        customerPreparation: "有代表性的销售或仓库场景、用户角色，以及用于判断流程是否满足需求的验收标准。",
        confirmation: "由谁配置与测试、必须通过哪些检查，以及由谁确认验收。访问权限、备份和生产环境变更均需事先商定准备工作并获得授权。"
      },
      rollout: {
        title: "准备培训与正式上线",
        body: "如商定提供培训或上线协助，需明确参与人员、任务和形式。上线日期根据已验证并约定的内容确定。",
        customerPreparation: "各应用的使用人员、可用于练习的时间，以及一位负责确认团队已就绪的人员。",
        confirmation: "商定哪些培训与上线协助及其范围、变更负责人、备份与恢复安排、问题反馈渠道和日期。"
      }
    },
    note: "此流程仅供参考，并非固定服务承诺。上线前须商定任务、负责人、迁移、培训及日期。打开本指南或申请演示不会修改生产数据、开通应用或产生购买。"
  }
};

export function getImplementationJourney(language: MarketingLanguage): ImplementationJourneyContent {
  return content[language];
}
