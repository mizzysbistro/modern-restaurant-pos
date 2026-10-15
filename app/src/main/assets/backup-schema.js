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

  // Match the ISO-style date-times emitted by the app and accepted by native
  // SQLite validation. Fail closed instead of letting Date.parse normalize
  // impossible dates or accept date-only strings.
  function isValidTimestamp(value) {
    if (typeof value !== "string" || value.length > 100) return false;
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?(Z|[+-](\d{2}):(\d{2}))?$/.exec(value);
    if (!match) return false;
    const year = Number(match[1]), month = Number(match[2]), day = Number(match[3]);
    const hour = Number(match[4]), minute = Number(match[5]), second = Number(match[6]);
    if (hour > 23 || minute > 59 || second > 59) return false;
    const calendar = new Date(0);
    calendar.setUTCFullYear(year, month - 1, day);
    calendar.setUTCHours(hour, minute, second, 0);
    if (calendar.getUTCFullYear() !== year || calendar.getUTCMonth() !== month - 1 ||
        calendar.getUTCDate() !== day || calendar.getUTCHours() !== hour ||
        calendar.getUTCMinutes() !== minute || calendar.getUTCSeconds() !== second) return false;
    if (match[8] && (Number(match[9]) > 23 || Number(match[10]) > 59)) return false;
    return Number.isFinite(Date.parse(value));
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
            typeof adjustment.time !== "string" || !isValidTimestamp(adjustment.time) ||
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

    if (state.openCart !== undefined) {
      if (!Array.isArray(state.openCart) || state.openCart.length > 500) {
        return failure("The backup contains an invalid open cart.");
      }
      const openCartIds = new Set();
      for (const line of state.openCart) {
        if (!line || typeof line !== "object" ||
            !isPositiveSafeInteger(line.id) || !productIds.has(line.id) ||
            openCartIds.has(line.id) || !Number.isSafeInteger(line.qty) ||
            line.qty < 1 || line.qty > 100000 ||
            (line.name !== undefined && (typeof line.name !== "string" || !line.name.trim() || line.name.length > 200)) ||
            (line.price !== undefined && (!Number.isFinite(line.price) || line.price < 0 || line.price > 100000000))) {
          return failure("The backup contains an invalid open cart.");
        }
        openCartIds.add(line.id);
      }
    }
    if (state.orderType !== undefined && state.orderType !== "Dine in" && state.orderType !== "Takeaway") {
      return failure("The backup contains invalid checkout context.");
    }
    if (state.tableNo !== undefined && (!Number.isSafeInteger(state.tableNo) || state.tableNo < 1 || state.tableNo > 12)) {
      return failure("The backup contains invalid checkout context.");
    }
    if (state.payment !== undefined && (typeof state.payment !== "string" || !state.payment.trim() || state.payment.length > 80)) {
      return failure("The backup contains invalid checkout context.");
    }
    if (state.customer !== undefined && (typeof state.customer !== "string" || state.customer.length > 200)) {
      return failure("The backup contains invalid checkout context.");
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
          typeof sale.time !== "string" || !isValidTimestamp(sale.time) ||
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
    const adjustmentEvents=state.saleAdjustments===undefined?[]:state.saleAdjustments;
    if(!Array.isArray(adjustmentEvents)||adjustmentEvents.length>10000)return failure("The backup contains invalid sale adjustment history.");
    const eventIds=new Set(),adjustmentState=new Map();let previousEventId=0;
    for(const event of adjustmentEvents){
      if(!event||typeof event!=="object"||!isPositiveSafeInteger(event.id)||event.id<=previousEventId||eventIds.has(event.id)||!isPositiveSafeInteger(event.saleId)||!salesById.has(event.saleId)||typeof event.time!=="string"||!isValidTimestamp(event.time)||(event.action!=="VOID"&&event.action!=="REFUND")||!Number.isSafeInteger(event.amountCents)||event.amountCents<0||typeof event.reason!=="string"||!event.reason.trim()||event.reason.length>160||typeof event.staff!=="string"||!event.staff.trim()||event.staff.length>80)return failure("The backup contains invalid sale adjustment history.");
      previousEventId=event.id;eventIds.add(event.id);const sale=salesById.get(event.saleId),totalCents=Math.round(sale.total*100);if(event.amountCents>totalCents||(event.action==="REFUND"&&event.amountCents<1))return failure("The backup contains invalid sale adjustment history.");
      const state=adjustmentState.get(event.saleId)||{action:event.action,amount:0,count:0};if(state.action!==event.action)return failure("The backup contains mixed void/refund history for one sale.");
      if(event.action==="VOID"){if(state.count>0||event.amountCents!==totalCents)return failure("The backup contains invalid sale adjustment history.");state.amount=totalCents}else{if(state.amount+event.amountCents>totalCents)return failure("The backup contains refund history beyond the sale total.");state.amount+=event.amountCents}state.count++;adjustmentState.set(event.saleId,state)
    }
    const openOrdersById = new Map();
    const openOrderTables = new Set();
    const openOrders = state.openOrders === undefined ? [] : state.openOrders;
    if (!Array.isArray(openOrders) || openOrders.length > 12) {
      return failure("The backup contains invalid open table orders.");
    }
    let highestOpenOrderId = 0;
    for (const order of openOrders) {
      if (!order || typeof order !== "object" ||
          !isPositiveSafeInteger(order.id) || saleIds.has(order.id) ||
          openOrdersById.has(order.id) || order.id >= state.nextId ||
          !Number.isSafeInteger(order.table) || order.table < 1 || order.table > 12 ||
          openOrderTables.has(order.table) ||
          typeof order.createdAt !== "string" || !isValidTimestamp(order.createdAt) ||
          (order.updatedAt !== undefined && (typeof order.updatedAt !== "string" || !isValidTimestamp(order.updatedAt))) ||
          (order.customer !== undefined && (typeof order.customer !== "string" || order.customer.length > 200)) ||
          !Array.isArray(order.items) || order.items.length < 1 || order.items.length > LIMITS.itemsPerSale) {
        return failure("The backup contains invalid open table orders.");
      }
      const itemIds = new Set();
      for (const item of order.items) {
        if (!item || typeof item !== "object" ||
            !isPositiveSafeInteger(item.id) || !productIds.has(item.id) || itemIds.has(item.id) ||
            typeof item.name !== "string" || !item.name.trim() || item.name.length > 200 ||
            !Number.isSafeInteger(item.qty) || item.qty < 1 || item.qty > 100000 ||
            !Number.isFinite(item.price) || item.price < 0 || item.price > 100000000) {
          return failure("The backup contains invalid open table order items.");
        }
        itemIds.add(item.id);
      }
      openOrdersById.set(order.id, order);
      openOrderTables.add(order.table);
      highestOpenOrderId = Math.max(highestOpenOrderId, order.id);
    }
    if (state.nextId <= Math.max(highestSaleId, highestOpenOrderId)) {
      return failure("The next order number must be higher than existing sales and open orders.");
    }
    if (state.activeOpenOrderId !== undefined && state.activeOpenOrderId !== null) {
      const activeOrder = openOrdersById.get(state.activeOpenOrderId);
      if (!activeOrder || state.tableNo !== activeOrder.table ||
          state.orderType !== "Dine in") {
        return failure("The backup contains invalid active table order context.");
      }
    }

    const ticketIds = new Set();
    for (const ticket of state.tickets) {
      if (!ticket || typeof ticket !== "object" ||
          !isPositiveSafeInteger(ticket.id) || ticketIds.has(ticket.id) ||
          !Array.isArray(ticket.items) || ticket.items.length > LIMITS.itemsPerTicket ||
          typeof ticket.time !== "string" || !isValidTimestamp(ticket.time) ||
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

    for (const [table, orderId] of Object.entries(state.tables)) {
      const tableNumber = Number(table);
      const openOrder = openOrdersById.get(orderId);
      const sale = salesById.get(orderId);
      if (!/^[1-9]\d*$/.test(table) || !Number.isSafeInteger(tableNumber) ||
          tableNumber < 1 || tableNumber > 12 || !isPositiveSafeInteger(orderId) ||
          (openOrder
            ? openOrder.table !== tableNumber
            : (!sale || sale.type !== "Dine in" || sale.table !== tableNumber))) {
        return failure("The backup contains invalid table occupancy data.");
      }
    }
    for (const order of openOrders) {
      if (state.tables[String(order.table)] !== order.id) {
        return failure("An open table order must own its table occupancy.");
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
