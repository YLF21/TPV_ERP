import { useCallback, useEffect, useMemo, useState } from "react";
import { apiRequest, type LocaleCode } from "@tpverp/app-common";

type GoodsCheckSummary = { id:string; documentId:string; documentNumber?:string|null; status:"ABIERTA"|"COMPLETA"|"CON_DIFERENCIAS"; createdAt:string; closedAt?:string|null; lineCount:number; differenceCount:number };
type StockCountSummary = { id:string; warehouseId:string; status:"DRAFT"|"CONFIRMED"|"CANCELLED"; notes?:string|null; createdAt:string; confirmedAt?:string|null; cancelledAt?:string|null; lineCount:number; totalDifference:number|string };
type WorkSummary = { id:string; type:string; status:"OPEN"|"DONE"|"CANCELLED"; title:string; createdAt:string; completedAt?:string|null; priority?:string|null; reference?:string|null; productCode?:string|null; quantity?:number|null; location?:string|null; notes?:string|null; createdBy?:string|null; assignedTo?:string|null };
type WarehouseOption = { id:string; name?:string|null; nombre?:string|null };
type HistoryKind="check"|"count"|"work";
export type PdaHistoryEvent={key:string;id:string;kind:HistoryKind;subtype?:string;at:string;title:string;status:string;lines:number;difference:number;raw:unknown};
type DetailLine={code?:string|null;name?:string|null;expectedQuantity?:number|string|null;registeredQuantity?:number|string|null;countedQuantity?:number|string|null;difference?:number|string|null;missingQuantity?:number|string|null;extraQuantity?:number|string|null};
type HistoryDetail={lines:DetailLine[];createdBy?:string|null;confirmedBy?:string|null;cancelledBy?:string|null;notes?:string|null};

export function matchesPdaHistoryStatus(status:string, filter:string){
 if(filter==="all")return true;
 if(filter==="open")return ["ABIERTA","DRAFT","OPEN"].includes(status);
 if(filter==="complete")return ["COMPLETA","CONFIRMED","DONE"].includes(status);
 if(filter==="difference")return status==="CON_DIFERENCIAS";
 return status==="CANCELLED";
}
export function filterPdaHistory(events:PdaHistoryEvent[],type:string,status:string,from:string,to:string){return events.filter(item=>{
 const day=item.at.slice(0,10); return (type==="all"||item.kind===type)&&matchesPdaHistoryStatus(item.status,status)&&(!from||day>=from)&&(!to||day<=to);
}).sort((a,b)=>new Date(b.at).getTime()-new Date(a.at).getTime())}

const copy={
 es:{dateFrom:"Desde",dateTo:"Hasta",status:"Estado",type:"Tipo",all:"Todos",checks:"Comprobaciones",counts:"Inventarios",work:"Operaciones",open:"Pendientes",complete:"Completadas",difference:"Con diferencias",cancelled:"Canceladas",detail:"Ver detalle",hide:"Cerrar detalle",loadingDetail:"Cargando detalle…",detailError:"No se pudo cargar el detalle.",product:"Producto",expected:"Esperada",registered:"Registrada",differenceLabel:"Diferencia",operator:"Operador",priority:"Prioridad",reference:"Referencia",location:"Ubicación",notes:"Notas",clear:"Limpiar filtros",results:"resultados"},
 en:{dateFrom:"From",dateTo:"To",status:"Status",type:"Type",all:"All",checks:"Goods checks",counts:"Stock counts",work:"Operations",open:"Open",complete:"Completed",difference:"With differences",cancelled:"Cancelled",detail:"View details",hide:"Close details",loadingDetail:"Loading details…",detailError:"Details could not be loaded.",product:"Product",expected:"Expected",registered:"Registered",differenceLabel:"Difference",operator:"Operator",priority:"Priority",reference:"Reference",location:"Location",notes:"Notes",clear:"Clear filters",results:"results"},
 zh:{dateFrom:"开始日期",dateTo:"结束日期",status:"状态",type:"类型",all:"全部",checks:"验货",counts:"盘点",work:"操作",open:"待处理",complete:"已完成",difference:"有差异",cancelled:"已取消",detail:"查看详情",hide:"关闭详情",loadingDetail:"正在加载详情…",detailError:"无法加载详情。",product:"商品",expected:"应有",registered:"已登记",differenceLabel:"差异",operator:"操作员",priority:"优先级",reference:"参考",location:"库位",notes:"备注",clear:"清除筛选",results:"条结果"}
} as const;

export function PdaHistory({ token, locale, warehouses, t }: {token?:string;locale:LocaleCode;warehouses:WarehouseOption[];t:(key:string)=>string}) {
 const c=copy[locale]; const [checks,setChecks]=useState<GoodsCheckSummary[]>([]); const [counts,setCounts]=useState<StockCountSummary[]>([]); const [work,setWork]=useState<WorkSummary[]>([]);
 const [type,setType]=useState("all"); const [status,setStatus]=useState("all"); const [from,setFrom]=useState(""); const [to,setTo]=useState("");
 const [selected,setSelected]=useState(""); const [details,setDetails]=useState<Record<string,HistoryDetail>>({}); const [detailLoading,setDetailLoading]=useState(""); const [detailError,setDetailError]=useState("");
 const [loading,setLoading]=useState(false); const [error,setError]=useState("");
 const localeTag=locale==="zh"?"zh-CN":locale==="en"?"en-GB":"es-ES"; const date=useMemo(()=>new Intl.DateTimeFormat(localeTag,{dateStyle:"medium",timeStyle:"short"}),[localeTag]); const number=useMemo(()=>new Intl.NumberFormat(localeTag,{maximumFractionDigits:3}),[localeTag]);
 const warehouseName=(id:string)=>warehouses.find(x=>x.id===id)?.name??warehouses.find(x=>x.id===id)?.nombre??id;
 const load=useCallback(async()=>{if(!token)return;setLoading(true);setError("");try{const [goods,stock,operations]=await Promise.all([apiRequest<GoodsCheckSummary[]>("/goods-checks",{token}),apiRequest<StockCountSummary[]>("/stock-counts",{token}),apiRequest<WorkSummary[]>("/pda-work",{token})]);setChecks(goods);setCounts(stock);setWork(operations)}catch{setError(t("pda.history.loadError"))}finally{setLoading(false)}},[t,token]);
 useEffect(()=>{void load()},[load]);
 const events=useMemo<PdaHistoryEvent[]>(()=>[
  ...checks.map(item=>({key:`check-${item.id}`,id:item.id,kind:"check" as const,at:item.closedAt??item.createdAt,title:item.documentNumber||item.documentId,status:item.status,lines:item.lineCount,difference:item.differenceCount,raw:item})),
  ...counts.map(item=>({key:`count-${item.id}`,id:item.id,kind:"count" as const,at:item.confirmedAt??item.cancelledAt??item.createdAt,title:warehouseName(item.warehouseId),status:item.status,lines:item.lineCount,difference:Number(item.totalDifference),raw:item})),
  ...work.map(item=>({key:`work-${item.id}`,id:item.id,kind:"work" as const,subtype:item.type,at:item.completedAt??item.createdAt,title:item.title,status:item.status,lines:item.quantity==null?0:Number(item.quantity),difference:0,raw:item}))
 ],[checks,counts,work,warehouses]);
 const visible=useMemo(()=>filterPdaHistory(events,type,status,from,to),[events,from,status,to,type]);
 async function toggleDetail(item:PdaHistoryEvent){if(selected===item.key){setSelected("");return}setSelected(item.key);setDetailError("");if(details[item.key])return;if(item.kind==="work"){const raw=item.raw as WorkSummary;setDetails(value=>({...value,[item.key]:{lines:[],createdBy:raw.createdBy,notes:raw.notes}}));return}if(!token)return;setDetailLoading(item.key);try{if(item.kind==="check"){const value=await apiRequest<{todos:DetailLine[]}>(`/goods-checks/${encodeURIComponent(item.id)}`,{token});setDetails(current=>({...current,[item.key]:{lines:value.todos??[]}}))}else{const value=await apiRequest<HistoryDetail>(`/stock-counts/${encodeURIComponent(item.id)}`,{token});setDetails(current=>({...current,[item.key]:{...value,lines:value.lines??[]}}))}}catch{setDetailError(item.key)}finally{setDetailLoading("")}}
 function clear(){setType("all");setStatus("all");setFrom("");setTo("")}
 return <section className="pda-history">
  <header className="pda-history-heading"><div><span>{t("pda.history.eyebrow")}</span><h2>{t("pda.history.title")}</h2><p>{t("pda.history.subtitle")}</p></div><button type="button" disabled={loading} onClick={()=>void load()}>{t("pda.history.refresh")}</button></header>
  <section className="pda-history-advanced-filters">
   <label><span>{c.type}</span><select value={type} onChange={e=>setType(e.target.value)}><option value="all">{c.all}</option><option value="check">{c.checks}</option><option value="count">{c.counts}</option><option value="work">{c.work}</option></select></label>
   <label><span>{c.status}</span><select value={status} onChange={e=>setStatus(e.target.value)}><option value="all">{c.all}</option><option value="open">{c.open}</option><option value="complete">{c.complete}</option><option value="difference">{c.difference}</option><option value="cancelled">{c.cancelled}</option></select></label>
   <label><span>{c.dateFrom}</span><input type="date" value={from} max={to||undefined} onChange={e=>setFrom(e.target.value)}/></label><label><span>{c.dateTo}</span><input type="date" value={to} min={from||undefined} onChange={e=>setTo(e.target.value)}/></label>
   <button type="button" onClick={clear}>{c.clear}</button>
  </section>
  <p className="pda-history-result-count">{visible.length} {c.results}</p>
  {error&&<p className="pda-count-error" role="alert">{error}</p>}{loading&&<p className="pda-history-empty">{t("common.loading")}</p>}{!loading&&visible.length===0&&<p className="pda-history-empty">{t("pda.history.empty")}</p>}
  <section className="pda-history-list">{visible.map(item=>{const raw=item.raw as WorkSummary;const detail=details[item.key];return <article key={item.key} className={selected===item.key?"expanded":""}>
   <div className={`pda-history-icon ${item.kind}`}>{item.kind==="check"?"✓":item.kind==="count"?"≣":"↗"}</div><header><span>{item.kind==="check"?c.checks:item.kind==="count"?c.counts:`${c.work} · ${item.subtype??""}`}</span><strong>{item.title}</strong><time>{date.format(new Date(item.at))}</time></header>
   <dl><div><dt>{item.kind==="work"?c.priority:t("pda.history.lines")}</dt><dd>{item.kind==="work"?(raw.priority??"—"):item.lines}</dd></div><div><dt>{item.kind==="work"?c.reference:t("pda.history.difference")}</dt><dd>{item.kind==="work"?(raw.reference??"—"):number.format(item.difference)}</dd></div></dl><span className={`pda-history-state state-${item.status.toLowerCase()}`}>{item.status}</span>
   <button className="pda-history-detail-toggle" type="button" onClick={()=>void toggleDetail(item)}>{selected===item.key?c.hide:c.detail}</button>
   {selected===item.key&&<section className="pda-history-detail">{detailLoading===item.key&&<p>{c.loadingDetail}</p>}{detailError===item.key&&<p role="alert">{c.detailError}</p>}{detail&&<>{detail.createdBy&&<p><b>{c.operator}:</b> {detail.createdBy}</p>}{raw.location&&<p><b>{c.location}:</b> {raw.location}</p>}{detail.notes&&<p><b>{c.notes}:</b> {detail.notes}</p>}{detail.lines.length>0&&<div className="pda-history-detail-lines">{detail.lines.map((line,index)=><article key={`${line.code??index}`}><strong>{line.code??"—"} · {line.name??c.product}</strong><span>{c.expected}: {number.format(Number(line.expectedQuantity??0))}</span><span>{c.registered}: {number.format(Number(line.registeredQuantity??line.countedQuantity??0))}</span><span>{c.differenceLabel}: {number.format(Number(line.difference??Number(line.extraQuantity??0)-Number(line.missingQuantity??0)))}</span></article>)}</div>}</>}</section>}
  </article>})}</section>
 </section>
}