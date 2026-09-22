import { useI18n } from "../../i18n";
const labels = {
  companyOnly: ["Datos de la sociedad", "Company information", "企业资料"],
  created: ["Empresa creada", "Company created", "企业已创建"],
  contact: ["Contacto", "Contact", "联系信息"],
  contactName: ["Nombre", "Name", "姓名"],
  contactOwner: ["Usar datos de propietario", "Use owner details", "使用所有者资料"],
  independentContact: ["Contacto independiente", "Independent contact", "独立联系人"],
  selectedOwnerHint: ["Mientras esté seleccionado, los cambios se aplican al propietario y al contacto de esta ficha.", "While selected, changes apply to both the owner and the contact in this form.", "选择所有者后，本表单中的修改同时应用于所有者和联系人。"],
  ownerPhoneRequired: ["Completa el teléfono del propietario seleccionado.", "Enter the selected owner's phone number.", "请填写所选所有者的电话号码。"],
  ownerEmailRequired: ["Completa el email del propietario seleccionado.", "Enter the selected owner's email address.", "请填写所选所有者的电子邮箱。"],
  newCompany: ["Alta nueva empresa", "New company", "新增企业"],
  tableHint: ["Doble clic o Intro para abrir la ficha de empresa.", "Double-click or press Enter to open a company profile.", "双击或按回车键打开企业资料。"],
  firstOwner: ["Propietario 1", "Owner 1", "所有者 1"],
  society: ["Sociedad", "Company", "法人企业"],
  selfEmployed: ["Autónomo", "Self-employed", "个体经营者"],
  createdAt: ["Fecha de alta", "Created on", "创建日期"],
  phone: ["Teléfono", "Phone", "电话"],
  owners: ["Propietarios", "Owners", "所有者"],
  owner: ["Propietario", "Owner", "所有者"],
  ownerName: ["Nombre completo", "Full name", "姓名"],
  ownerTaxId: ["DNI/NIE", "DNI/NIE", "DNI/NIE 身份证号"],
  addOwner: ["Añadir propietario", "Add owner", "添加所有者"],
  removeOwner: ["Eliminar propietario", "Remove owner", "删除所有者"],
  ownersRequired: ["Debe haber al menos un propietario con nombre completo y DNI/NIE.", "At least one owner with a full name and DNI/NIE is required.", "必须至少填写一位所有者的姓名和 DNI/NIE 身份证号。"],
  saveProfile: ["Guardar ficha", "Save profile", "保存资料"],
  saved: ["Ficha de empresa guardada.", "Company profile saved.", "企业资料已保存。"],
  identityLocked: ["NIF/CIF y tipo de obligado conservan la identidad fiscal de la sociedad.", "Tax ID and taxpayer type preserve the company's fiscal identity.", "税号和纳税人类型保留企业的税务身份。"],
  empty: ["SIN DATOS", "NO DATA", "暂无数据"],
} as const;
export function useCompanyLabels() {
  const { language } = useI18n();
  return (key: keyof typeof labels) => labels[key][language === "zh" ? 2 : language === "en" ? 1 : 0];
}
