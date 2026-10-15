const test = require("node:test");
const assert = require("node:assert/strict");
const { summarizeSales, dateKeyForLocalDate } = require("../app/src/main/assets/reporting.js");
function sale(id, time, total, payment) {
  return { id, time, total, payment, type:"Takeaway", table:null, items:[] };
}
test("includes both boundary dates and treats the end date as inclusive", () => {
  const rows = [
    sale(1,"2026-10-14T23:59:59.999",10,"Cash"),
    sale(2,"2026-10-15T00:00:00.000",20,"Cash"),
    sale(3,"2026-10-15T23:59:59.999",30,"Card"),
    sale(4,"2026-10-16T00:00:00.000",40,"Cash")
  ];
  const result=summarizeSales(rows,"2026-10-15","2026-10-15");
  assert.equal(result.ok,true);
  assert.deepEqual(result.sales.map(item=>item.id),[2,3]);
  assert.equal(result.gross,50);
});
test("groups payment totals and calculates average order from persisted sales", () => {
  const result=summarizeSales([
    sale(1,"2026-10-15T09:00:00",100.25,"Cash"),
    sale(2,"2026-10-15T10:00:00",39.75,"Cash"),
    sale(3,"2026-10-15T11:00:00",60,"Card")
  ],"2026-10-15","2026-10-15");
  assert.equal(result.ok,true);
  assert.equal(result.orderCount,3);
  assert.equal(result.gross,200);
  assert.equal(result.averageOrder,66.67);
  assert.deepEqual(result.paymentBreakdown,[
    {payment:"Cash",orders:2,total:140},
    {payment:"Card",orders:1,total:60}
  ]);
});
test("rejects invalid calendar dates and reversed ranges", () => {
  assert.equal(summarizeSales([],"2026-02-30","2026-03-01").ok,false);
  assert.match(summarizeSales([],"2026-10-16","2026-10-15").error,/start date must be on or before/);
});
test("returns zero values for a valid range with no recorded sales", () => {
  const result=summarizeSales([],"2026-10-15","2026-10-15");
  assert.equal(result.ok,true);
  assert.equal(result.orderCount,0);
  assert.equal(result.gross,0);
  assert.equal(result.averageOrder,0);
  assert.deepEqual(result.paymentBreakdown,[]);
});
test("dateKeyForLocalDate uses local calendar fields", () => {
  assert.equal(dateKeyForLocalDate(new Date(2026,9,15,23,30)),"2026-10-15");
  assert.equal(dateKeyForLocalDate(new Date("invalid")),"");
});
