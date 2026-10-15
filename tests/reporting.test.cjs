const test = require("node:test");
const assert = require("node:assert/strict");
const { summarizeSales, dateKeyForLocalDate, createSaleAdjustment, LIMIT } = require("../app/src/main/assets/reporting.js");
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

test("net reports reflect partial refunds and exclude void amounts",()=>{
 const rows=[sale(1,"2026-10-15T09:00:00",100,"Cash"),sale(2,"2026-10-15T10:00:00",50,"Card"),sale(3,"2026-10-15T11:00:00",25,"Cash")];
 const adj=[{id:1,time:"2026-10-15T12:00:00Z",saleId:1,action:"REFUND",amountCents:2500,reason:"Partial",staff:"Mizzy"},{id:2,time:"2026-10-15T12:01:00Z",saleId:2,action:"VOID",amountCents:5000,reason:"Void",staff:"Mizzy"}];
 const result=summarizeSales(rows,"2026-10-15","2026-10-15",adj);
 assert.equal(result.ok,true);assert.equal(result.gross,100);assert.equal(result.orderCount,2);assert.equal(result.sales.find(s=>s.id===1).netCents,7500);assert.equal(result.sales.find(s=>s.id===2).adjustmentStatus,"VOID");assert.deepEqual(result.paymentBreakdown,[{payment:"Cash",orders:2,total:100}]);
});


function adjustmentInput(overrides = {}) {
  return {
    sales: [sale(10, "2026-10-15T09:00:00.000Z", 100, "Cash")],
    adjustments: [],
    saleId: 10,
    action: "REFUND",
    amountCents: 2500,
    reason: "Order issue",
    staff: "Cashier",
    time: "2026-10-15T10:00:00.000Z",
    ...overrides
  };
}

test("creates a valid refund with an increasing audit ID", () => {
  const existing = [
    { id: 4, time: "2026-10-15T09:30:00.000Z", saleId: 99, action: "REFUND", amountCents: 500, reason: "Other sale", staff: "Mizzy" },
    { id: 7, time: "2026-10-15T09:45:00.000Z", saleId: 10, action: "REFUND", amountCents: 1000, reason: "First refund", staff: "Mizzy" }
  ];
  const result = createSaleAdjustment(adjustmentInput({ adjustments: existing, amountCents: 2000 }));
  assert.equal(result.ok, true);
  assert.deepEqual(result.adjustment, {
    id: 8,
    time: "2026-10-15T10:00:00.000Z",
    saleId: 10,
    action: "REFUND",
    amountCents: 2000,
    reason: "Order issue",
    staff: "Cashier"
  });
});

test("rejects a refund that exceeds the remaining balance after earlier refunds", () => {
  const result = createSaleAdjustment(adjustmentInput({
    adjustments: [
      { id: 1, time: "2026-10-15T09:30:00.000Z", saleId: 10, action: "REFUND", amountCents: 7000, reason: "First refund", staff: "Mizzy" }
    ],
    amountCents: 3001
  }));
  assert.equal(result.ok, false);
  assert.match(result.error, /remaining sale balance/);
});

test("rejects zero, negative, fractional, and unsafe refund amounts", () => {
  for (const amountCents of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
    const result = createSaleAdjustment(adjustmentInput({ amountCents }));
    assert.equal(result.ok, false, "amount " + String(amountCents) + " must be rejected");
    assert.match(result.error, /amount must be within the remaining sale balance/);
  }
});

test("rejects voiding a partially refunded sale", () => {
  const result = createSaleAdjustment(adjustmentInput({
    action: "VOID",
    amountCents: 10000,
    adjustments: [
      { id: 1, time: "2026-10-15T09:30:00.000Z", saleId: 10, action: "REFUND", amountCents: 1000, reason: "First refund", staff: "Mizzy" }
    ]
  }));
  assert.equal(result.ok, false);
  assert.match(result.error, /partially refunded sale cannot be voided/);
});

test("void requires the entire original sale amount", () => {
  const result = createSaleAdjustment(adjustmentInput({ action: "VOID", amountCents: 9999 }));
  assert.equal(result.ok, false);
  assert.match(result.error, /amount must be within the remaining sale balance/);
});

test("rejects further adjustments after a full refund or void", () => {
  const terminalEvents = [
    [{ id: 1, time: "2026-10-15T09:30:00.000Z", saleId: 10, action: "REFUND", amountCents: 10000, reason: "Full refund", staff: "Mizzy" }],
    [{ id: 1, time: "2026-10-15T09:30:00.000Z", saleId: 10, action: "VOID", amountCents: 10000, reason: "Void sale", staff: "Mizzy" }]
  ];
  for (const adjustments of terminalEvents) {
    const result = createSaleAdjustment(adjustmentInput({ adjustments, amountCents: 1 }));
    assert.equal(result.ok, false);
    assert.match(result.error, /already been fully adjusted/);
  }
});

test("requires a nonblank bounded reason and staff name plus a valid timestamp", () => {
  const invalidInputs = [
    { reason: "   " },
    { reason: "r".repeat(161) },
    { staff: " " },
    { staff: "s".repeat(81) },
    { time: "not-a-date" }
  ];
  for (const overrides of invalidInputs) {
    const result = createSaleAdjustment(adjustmentInput(overrides));
    assert.equal(result.ok, false);
    assert.match(result.error, /reason|staff name|adjustment time/i);
  }
});

test("refuses new adjustment events once the audit history reaches its limit", () => {
  const result = createSaleAdjustment(adjustmentInput({
    adjustments: Array.from({ length: LIMIT }, (_, index) => ({
      id: index + 1,
      time: "2026-10-15T09:30:00.000Z",
      saleId: 99,
      action: "VOID",
      amountCents: 500,
      reason: "Other sale",
      staff: "Mizzy"
    }))
  }));
  assert.equal(result.ok, false);
  assert.match(result.error, /history is full/);
});


test("fails closed when a saved sale has an invalid timestamp", () => {
  const result = summarizeSales([
    sale(1, "2026-10-15T09:00:00.000Z", 10, "Cash"),
    sale(2, "not-a-date", 40, "Cash")
  ], "2026-10-15", "2026-10-15");
  assert.equal(result.ok, false);
  assert.match(result.error, /invalid timestamp/);
  assert.deepEqual(result.sales, []);
});

test("fails closed instead of silently omitting invalid totals in the selected date range", () => {
  for (const total of [-1, Infinity, NaN, Number.MAX_VALUE]) {
    const result = summarizeSales([
      sale(1, "2026-10-15T09:00:00.000Z", total, "Cash")
    ], "2026-10-15", "2026-10-15");
    assert.equal(result.ok, false, "invalid total " + String(total) + " must fail the report");
    assert.match(result.error, /invalid sale record/);
  }
});

test("ignores an invalid total on a correctly dated sale outside the selected range", () => {
  const result = summarizeSales([
    sale(1, "2026-10-14T09:00:00.000Z", -1, "Cash"),
    sale(2, "2026-10-15T09:00:00.000Z", 20, "Cash")
  ], "2026-10-15", "2026-10-15");
  assert.equal(result.ok, true);
  assert.deepEqual(result.sales.map(item => item.id), [2]);
  assert.equal(result.gross, 20);
});
