(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module && module.exports) module.exports = api;
  if (root) root.MizzyReports = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  function parseLocalDateKey(value) {
    if (typeof value !== "string") return null;
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (!match) return null;
    const year = Number(match[1]), month = Number(match[2]), day = Number(match[3]);
    const date = new Date(year, month - 1, day);
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null;
    return date;
  }
  function dateKeyForLocalDate(date) {
    if (!(date instanceof Date) || !Number.isFinite(date.getTime())) return "";
    return date.getFullYear() + "-" + String(date.getMonth() + 1).padStart(2, "0") + "-" + String(date.getDate()).padStart(2, "0");
  }
  function failure(error) {
    return { ok:false, error, sales:[], orderCount:0, gross:0, averageOrder:0, paymentBreakdown:[] };
  }
  function summarizeSales(sales, startDate, endDate) {
    if (!Array.isArray(sales)) return failure("Sales data is not available.");
    const start = parseLocalDateKey(startDate), end = parseLocalDateKey(endDate);
    if (!start || !end) return failure("Choose valid report start and end dates.");
    if (start.getTime() > end.getTime()) return failure("The report start date must be on or before the end date.");
    const endExclusive = new Date(end.getTime());
    endExclusive.setDate(endExclusive.getDate() + 1);
    const rows = sales.filter((sale) => {
      if (!sale || typeof sale !== "object") return false;
      const timestamp = Date.parse(sale.time), total = sale.total;
      return Number.isFinite(timestamp) && timestamp >= start.getTime() && timestamp < endExclusive.getTime() &&
        Number.isFinite(total) && total >= 0 && Number.isSafeInteger(Math.round(total * 100));
    });
    let grossCents = 0;
    const payments = new Map();
    for (const sale of rows) {
      const cents = Math.round(sale.total * 100);
      if (!Number.isSafeInteger(grossCents + cents)) return failure("The selected range exceeds the safe reporting total.");
      grossCents += cents;
      const payment = typeof sale.payment === "string" && sale.payment.trim() ? sale.payment.trim() : "Unspecified";
      const item = payments.get(payment) || { payment, orders:0, totalCents:0 };
      if (!Number.isSafeInteger(item.totalCents + cents)) return failure("A payment-method total exceeds the safe reporting range.");
      item.orders += 1;
      item.totalCents += cents;
      payments.set(payment, item);
    }
    const orderCount = rows.length;
    const paymentBreakdown = Array.from(payments.values())
      .map((item) => ({ payment:item.payment, orders:item.orders, total:item.totalCents / 100 }))
      .sort((a, b) => b.total - a.total || a.payment.localeCompare(b.payment));
    return {
      ok:true, startDate, endDate, sales:rows, orderCount, gross:grossCents / 100,
      averageOrder:orderCount ? Math.round(grossCents / orderCount) / 100 : 0,
      paymentBreakdown
    };
  }
  return Object.freeze({ summarizeSales, dateKeyForLocalDate });
});
