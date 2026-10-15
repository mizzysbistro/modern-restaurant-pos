(function(root,factory){const api=factory();if(typeof module==="object"&&module&&module.exports)module.exports=api;if(root)root.MizzyReports=api})(typeof globalThis!=="undefined"?globalThis:this,function(){"use strict";
const LIMIT=10000;
function summarizeSale(sale,events){
 if(!sale||!Number.isSafeInteger(Number(sale.id))||Number(sale.id)<1||!Number.isFinite(Number(sale.total))||Number(sale.total)<0)return{ok:false,error:"The saved sale is invalid."};
 if(!Array.isArray(events))return{ok:false,error:"Sale adjustment history is not available."};
 const totalCents=Math.round(Number(sale.total)*100);if(!Number.isSafeInteger(totalCents))return{ok:false,error:"The sale total exceeds the safe reporting range."};
 const rows=events.filter(e=>e&&Number(e.saleId)===Number(sale.id));if(!rows.length)return{ok:true,status:"PAID",totalCents,adjustedCents:0,netCents:totalCents,eventCount:0};
 const action=rows[0].action;if(action==="VOID"){if(rows.length!==1||Number(rows[0].amountCents)!==totalCents)return{ok:false,error:"The saved void history is inconsistent."};return{ok:true,status:"VOID",totalCents,adjustedCents:totalCents,netCents:0,eventCount:1}}
 if(action!=="REFUND")return{ok:false,error:"The sale adjustment type is invalid."};let adjustedCents=0;
 for(const row of rows){if(row.action!=="REFUND"||!Number.isSafeInteger(row.amountCents)||row.amountCents<1||!Number.isSafeInteger(adjustedCents+row.amountCents))return{ok:false,error:"The saved refund history is inconsistent."};adjustedCents+=row.amountCents}
 if(adjustedCents>totalCents)return{ok:false,error:"Refund history exceeds the original sale total."};
 return{ok:true,status:adjustedCents===totalCents?"REFUNDED":"PARTIALLY REFUNDED",totalCents,adjustedCents,netCents:totalCents-adjustedCents,eventCount:rows.length}
}
function createSaleAdjustment(d){
 d=d&&typeof d==="object"?d:{};if(!Array.isArray(d.sales)||!Array.isArray(d.adjustments))return{ok:false,error:"Sales and audit history are required."};
 const saleId=Number(d.saleId),sale=d.sales.find(s=>s&&Number(s.id)===saleId);if(!sale)return{ok:false,error:"The saved sale could not be found."};
 if(d.adjustments.length>=LIMIT)return{ok:false,error:"Sale adjustment history is full. Back up POS data before continuing."};
 const before=summarizeSale(sale,d.adjustments);if(!before.ok)return before;
 if(d.action!=="VOID"&&d.action!=="REFUND")return{ok:false,error:"Choose VOID or REFUND."};if(before.status==="VOID"||before.status==="REFUNDED")return{ok:false,error:"This sale has already been fully adjusted."};if(d.action==="VOID"&&before.status!=="PAID")return{ok:false,error:"A partially refunded sale cannot be voided."};
 if(!Number.isSafeInteger(d.amountCents)||d.amountCents<0||d.amountCents>before.netCents||(d.action==="REFUND"&&d.amountCents<1)||(d.action==="VOID"&&d.amountCents!==before.totalCents))return{ok:false,error:"Adjustment amount must be within the remaining sale balance."};
 const reason=typeof d.reason==="string"?d.reason.trim():"",staff=typeof d.staff==="string"?d.staff.trim():"",time=typeof d.time==="string"?d.time:"";
 if(!reason||reason.length>160)return{ok:false,error:"Enter a reason of 1–160 characters."};if(!staff||staff.length>80)return{ok:false,error:"Enter a staff name of 1–80 characters."};if(!time||time.length>100||!Number.isFinite(Date.parse(time)))return{ok:false,error:"A valid adjustment time is required."};
 const maxId=d.adjustments.reduce((m,e)=>Math.max(m,Number(e&&e.id)||0),0);if(!Number.isSafeInteger(maxId+1))return{ok:false,error:"The audit event ID limit was reached."};
 return{ok:true,adjustment:{id:maxId+1,time,saleId,action:d.action,amountCents:d.amountCents,reason,staff}}
}
function parseLocalDateKey(value){if(typeof value!=="string")return null;const m=/^(\d{4})-(\d{2})-(\d{2})$/.exec(value);if(!m)return null;const y=Number(m[1]),mo=Number(m[2]),d=Number(m[3]),date=new Date(y,mo-1,d);if(date.getFullYear()!==y||date.getMonth()!==mo-1||date.getDate()!==d)return null;return date}
function dateKeyForLocalDate(date){if(!(date instanceof Date)||!Number.isFinite(date.getTime()))return"";return date.getFullYear()+"-"+String(date.getMonth()+1).padStart(2,"0")+"-"+String(date.getDate()).padStart(2,"0")}
function failure(error){return{ok:false,error,sales:[],orderCount:0,gross:0,averageOrder:0,paymentBreakdown:[]}}
function summarizeSales(sales,startDate,endDate,saleAdjustments=[]){
 if(!Array.isArray(sales))return failure("Sales data is not available.");if(!Array.isArray(saleAdjustments))return failure("Sale adjustment history is not available.");
 const start=parseLocalDateKey(startDate),end=parseLocalDateKey(endDate);if(!start||!end)return failure("Choose valid report start and end dates.");if(start.getTime()>end.getTime())return failure("The report start date must be on or before the end date.");
 const endExclusive=new Date(end.getTime());endExclusive.setDate(endExclusive.getDate() + 1);
 const rows=[];for(const s of sales){if(!s||typeof s!=="object"||Array.isArray(s))return failure("Sales data contains an invalid sale record.");const t=Date.parse(s.time);if(!Number.isFinite(t))return failure("Sales data contains a sale with an invalid timestamp; no report was generated.");if(t<start.getTime()||t>=endExclusive.getTime())continue;const total=s.total;if(!Number.isFinite(total)||total<0||!Number.isSafeInteger(Math.round(total*100)))return failure("Sales data contains an invalid sale record; no report was generated.");rows.push(s)}
 let grossCents=0,orderCount=0;const payments=new Map(),adjustedRows=[];
 for(const sale of rows){const a=summarizeSale(sale,saleAdjustments);if(!a.ok)return failure(a.error);if(!Number.isSafeInteger(grossCents+a.netCents))return failure("The selected range exceeds the safe reporting total.");grossCents+=a.netCents;adjustedRows.push({...sale,adjustmentStatus:a.status,adjustedCents:a.adjustedCents,netCents:a.netCents});if(a.netCents>0){orderCount++;const payment=typeof sale.payment==="string"&&sale.payment.trim()?sale.payment.trim():"Unspecified",item=payments.get(payment)||{payment,orders:0,totalCents:0};if(!Number.isSafeInteger(item.totalCents+a.netCents))return failure("A payment-method total exceeds the safe reporting range.");item.orders++;item.totalCents+=a.netCents;payments.set(payment,item)}}
 const paymentBreakdown=Array.from(payments.values()).map(x=>({payment:x.payment,orders:x.orders,total:x.totalCents/100})).sort((a,b)=>b.total-a.total||a.payment.localeCompare(b.payment));
 return{ok:true,startDate,endDate,sales:adjustedRows,orderCount,gross:grossCents/100,averageOrder:orderCount?Math.round(grossCents/orderCount)/100:0,paymentBreakdown}
}
return Object.freeze({summarizeSales,dateKeyForLocalDate,summarizeSale,createSaleAdjustment,LIMIT})
});
