(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module && module.exports) module.exports = api;
  if (root) root.MizzyBackup = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const FORMAT = "mizzy-bistro-pos-backup";
  const SCHEMA_VERSION = 1;
  const APP_ID = "com.mizzysbistro.pos";
  const LIMITS = Object.freeze({
    products: 5000,
    sales: 100000,
    tickets: 10000,
    itemsPerSale: 500,
    itemsPerTicket: 500
  });

  function failure(error) {
    return { ok: false, error };
  }

  function isPositiveSafeInteger(value) {
    return Number.isSafeInteger(value) && value > 0;
  }

  function validateBackupDocument(doc) {
    if (!doc || typeof doc !== "object" || Array.isArray(doc) ||
        doc.format !== FORMAT || doc.schemaVersion !== SCHEMA_VERSION ||
        doc.appId !== APP_ID) {
      return failure("This is not a supported Mizzy’s Bistro POS backup.");
    }

    const state = doc.state;
    if (!state || typeof state !== "object" || Array.isArray(state) ||
        !Array.isArray(state.products) || !Array.isArray(state.sales) ||
        !Array.isArray(state.tickets) || !state.stock ||
        typeof state.stock !== "object" || Array.isArray(state.stock) ||
        !state.tables || typeof state.tables !== "object" ||
        Array.isArray(state.tables) ||
        !isPositiveSafeInteger(state.nextId)) {
      return failure("The backup is missing required POS data.");
    }

    if (state.stockAdjustments !== undefined) {
      if (!Array.isArray(state.stockAdjustments) || state.stockAdjustments.length > 10000) {
        return failure("The backup contains invalid stock adjustment history.");
      }
      const adjustmentIds = new Set();
      for (const adjustment of state.stockAdjustments) {
        if (!adjustment || typeof adjustment !== "object" ||
            !isPositiveSafeInteger(adjustment.id) || adjustmentIds.has(adjustment.id) ||
            !isPositiveSafeInteger(adjustment.productId) ||
            typeof adjustment.time !== "string" || !Number.isFinite(Date.parse(adjustment.time)) ||
            typeof adjustment.productName !== "string" || !adjustment.productName.trim() || adjustment.productName.length > 200 ||
            !Number.isSafeInteger(adjustment.from) || adjustment.from < 0 || adjustment.from > 100000000 ||
            !Number.isSafeInteger(adjustment.to) || adjustment.to < 0 || adjustment.to > 100000000 ||
            !Number.isSafeInteger(adjustment.delta) || adjustment.delta !== adjustment.to - adjustment.from ||
            typeof adjustment.reason !== "string" || !adjustment.reason.trim() || adjustment.reason.length > 160 ||
            (adjustment.source !== "Manual" && adjustment.source !== "Sale")) {
          return failure("The backup contains invalid stock adjustment history.");
        }
        adjustmentIds.add(adjustment.id);
      }
    }

    if (state.products.length > LIMITS.products ||
        state.sales.length > LIMITS.sales ||
        state.tickets.length > LIMITS.tickets) {
      return failure("The backup contains too many records to restore safely.");
    }

    const productIds = new Set();
    for (const product of state.products) {
      if (!product || typeof product !== "object" ||
          !isPositiveSafeInteger(product.id) || productIds.has(product.id) ||
          typeof product.name !== "string" || !product.name.trim() ||
          product.name.length > 200 || typeof product.cat !== "string" ||
          !product.cat.trim() || product.cat.length > 80 ||
          !Number.isFinite(product.price) || product.price <= 0 ||
          product.price > 100000000) {
        return failure("The backup contains an invalid menu item.");
      }
      productIds.add(product.id);
    }

    for (const [id, quantity] of Object.entries(state.stock)) {
      if (!/^\d+$/.test(id) || !isPositiveSafeInteger(Number(id)) ||
          !Number.isSafeInteger(quantity) || quantity < 0 ||
          quantity > 100000000) {
        return failure("The backup contains an invalid stock quantity.");
      }
    }

    const saleIds = new Set();
    const salesById = new Map();
    let highestSaleId = 0;
    for (const sale of state.sales) {
      if (!sale || typeof sale !== "object" ||
          !isPositiveSafeInteger(sale.id) || saleIds.has(sale.id) ||
          typeof sale.time !== "string" || !Number.isFinite(Date.parse(sale.time)) ||
          !Array.isArray(sale.items) || sale.items.length < 1 || sale.items.length > LIMITS.itemsPerSale ||
          !Number.isFinite(sale.total) || sale.total < 0 ||
          sale.total > 1000000000 || typeof sale.payment !== "string" ||
          sale.payment.length > 80 ||
          (sale.type !== "Dine in" && sale.type !== "Takeaway") ||
          (sale.type === "Dine in" && (!Number.isSafeInteger(sale.table) || sale.table < 1 || sale.table > 12)) ||
          (sale.type === "Takeaway" && sale.table != null)) {
        return failure("The backup contains an invalid sale record.");
      }
      saleIds.add(sale.id);
      salesById.set(sale.id, sale);
      highestSaleId = Math.max(highestSaleId, sale.id);
      let itemsTotal = 0;
      for (const item of sale.items) {
        if (!item || typeof item !== "object" ||
            typeof item.name !== "string" || !item.name.trim() ||
            item.name.length > 200 || !Number.isSafeInteger(item.qty) ||
            item.qty < 1 || item.qty > 100000 ||
            !Number.isFinite(item.price) || item.price < 0 ||
            item.price > 100000000) {
          return failure("The backup contains invalid sale items.");
        }
        itemsTotal += item.price * item.qty;
        if (!Number.isFinite(itemsTotal) || itemsTotal > 1000000000) {
          return failure("The backup contains invalid sale totals.");
        }
      }
      if (Math.round(itemsTotal * 100) !== Math.round(sale.total * 100)) {
        return failure("The backup sale total does not match its items.");
      }
    }
    if (state.nextId <= highestSaleId) {
      return failure("The next order number must be higher than existing sales.");
    }

    const ticketIds = new Set();
    for (const ticket of state.tickets) {
      if (!ticket || typeof ticket !== "object" ||
          !isPositiveSafeInteger(ticket.id) || ticketIds.has(ticket.id) ||
          !Array.isArray(ticket.items) || ticket.items.length > LIMITS.itemsPerTicket ||
          typeof ticket.time !== "string" || !Number.isFinite(Date.parse(ticket.time)) ||
          typeof ticket.done !== "boolean" ||
          (ticket.type !== "Dine in" && ticket.type !== "Takeaway") ||
          (ticket.type === "Dine in" && (!Number.isSafeInteger(ticket.table) || ticket.table < 1 || ticket.table > 12)) ||
          (ticket.type === "Takeaway" && ticket.table != null)) {
        return failure("The backup contains an invalid kitchen ticket.");
      }
      ticketIds.add(ticket.id);
      for (const item of ticket.items) {
        if (!item || typeof item.name !== "string" || !item.name.trim() ||
            !Number.isSafeInteger(item.qty) || item.qty < 1 || item.qty > 100000) {
          return failure("The backup contains invalid kitchen items.");
        }
      }
    }

    for (const [table, saleId] of Object.entries(state.tables)) {
      const tableNumber = Number(table);
      const sale = salesById.get(saleId);
      if (!/^[1-9]\d*$/.test(table) || !Number.isSafeInteger(tableNumber) ||
          tableNumber < 1 || tableNumber > 12 || !isPositiveSafeInteger(saleId) ||
          !sale || sale.type !== "Dine in" || sale.table !== tableNumber) {
        return failure("The backup contains invalid table occupancy data.");
      }
    }

    return { ok: true, state };
  }

  return Object.freeze({
    format: FORMAT,
    schemaVersion: SCHEMA_VERSION,
    appId: APP_ID,
    validateBackupDocument
  });
});
