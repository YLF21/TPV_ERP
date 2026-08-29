import { describe, expect, it } from "vitest";
import { filterPdaHistory, matchesPdaHistoryStatus, type PdaHistoryEvent } from "./PdaHistory";
const events:PdaHistoryEvent[]=[
 {key:"c1",id:"1",kind:"check",at:"2026-08-20T10:00:00Z",title:"A",status:"ABIERTA",lines:1,difference:0,raw:{}},
 {key:"c2",id:"2",kind:"count",at:"2026-08-22T10:00:00Z",title:"B",status:"CONFIRMED",lines:2,difference:1,raw:{}},
 {key:"w1",id:"3",kind:"work",at:"2026-08-25T10:00:00Z",title:"C",status:"CANCELLED",lines:0,difference:0,raw:{}}
];
describe("PdaHistory filters",()=>{
 it("groups API statuses",()=>{expect(matchesPdaHistoryStatus("DRAFT","open")).toBe(true);expect(matchesPdaHistoryStatus("DONE","complete")).toBe(true);expect(matchesPdaHistoryStatus("CON_DIFERENCIAS","difference")).toBe(true)});
 it("combines type status and inclusive dates",()=>{expect(filterPdaHistory(events,"count","complete","2026-08-22","2026-08-22").map(x=>x.key)).toEqual(["c2"]);expect(filterPdaHistory(events,"all","all","2026-08-21","2026-08-25").map(x=>x.key)).toEqual(["w1","c2"])});
});