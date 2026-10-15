const test = require("node:test");
const assert = require("node:assert/strict");
const { validateBackupDocument } = require("../app/src/main/assets/backup-schema.js");

function validBackup() {
  return {
    format: "mizzy-bistro-pos-backup",
    schemaVersion: 1,
    exportedAt: "2026-10-15T10:00:00.000Z",
    appId: "com.mizzysbistro.pos",
    state: {
      products: [
        { id: 1, name: "Chicken Plate", cat: "Mains", price: 245, emoji: "🍗", art: "art1", tag: "Popular" }
      ],
      openCart: [{ id: 1, qty: 2 }],
      orderType: "Dine in",
      tableNo: 4,
      payment: "Cash",
      customer: "",
      sales: [
        {
          id: 1001, time: "2026-10-15T10:00:00.000Z", type: "Dine in",
          table: 2, payment: "Cash", total: 245,
          items: [{ name: "Chicken Plate", price: 245, qty: 1 }]
        }
      ],
      stock: { "1": 9 },
      stockAdjustments: [
        { id: 1, time: "2026-10-15T10:01:00.000Z", productId: 1, productName: "Chicken Plate", from: 10, to: 9, delta: -1, reason: "Sale #1000", source: "Sale" }
      ],
      tickets: [
        {
          id: 1001, time: "2026-10-15T10:00:00.000Z", type: "Dine in",
          table: 2, done: false,
          items: [{ name: "Chicken Plate", price: 245, qty: 1 }]
        }
      ],
      tables: { "2": 1001 },
      nextId: 1002
    }
  };
}

test("accepts a valid POS backup and returns its state", () => {
  const backup = validBackup();
  const result = validateBackupDocument(backup);
  assert.equal(result.ok, true);
  assert.equal(result.state, backup.state);
});

test("rejects files that are not the supported app backup format", () => {
  const backup = validBackup();
  backup.format = "other-app";
  assert.match(validateBackupDocument(backup).error, /not a supported/);
});

test("rejects missing required state sections", () => {
  const backup = validBackup();
  delete backup.state.sales;
  assert.match(validateBackupDocument(backup).error, /missing required/);
});

test("rejects duplicate product IDs", () => {
  const backup = validBackup();
  backup.state.products.push({ ...backup.state.products[0] });
  assert.match(validateBackupDocument(backup).error, /invalid menu item/);
});

test("rejects empty product names and invalid prices", () => {
  const backup = validBackup();
  backup.state.products[0].price = 0;
  assert.match(validateBackupDocument(backup).error, /invalid menu item/);
});

test("rejects negative or non-integer stock", () => {
  const backup = validBackup();
  backup.state.stock["1"] = -1;
  assert.match(validateBackupDocument(backup).error, /invalid stock/);
});

test("rejects malformed or duplicate sale records", () => {
  const backup = validBackup();
  backup.state.sales.push({ ...backup.state.sales[0] });
  assert.match(validateBackupDocument(backup).error, /invalid sale record/);
});

test("rejects a next order number that may collide with existing sales", () => {
  const backup = validBackup();
  backup.state.nextId = 1001;
  assert.match(validateBackupDocument(backup).error, /higher than existing sales/);
});

test("rejects malformed sale items", () => {
  const backup = validBackup();
  backup.state.sales[0].items[0].qty = 0;
  assert.match(validateBackupDocument(backup).error, /invalid sale items/);
});

test("rejects invalid table occupancy", () => {
  const backup = validBackup();
  backup.state.tables["2"] = -10;
  assert.match(validateBackupDocument(backup).error, /table occupancy/);
});

test("rejects unreasonably large menu and sales collections", () => {
  const backup = validBackup();
  backup.state.products = Array.from({ length: 5001 }, (_, index) => ({
    id: index + 1, name: "Item " + index, cat: "Mains", price: 1
  }));
  assert.match(validateBackupDocument(backup).error, /too many records/);
});

test("rejects a dine-in sale assigned to an invalid table", () => {
  const backup = validBackup();
  backup.state.sales[0].table = 99;
  assert.match(validateBackupDocument(backup).error, /invalid sale record/);
});

test("rejects kitchen tickets without an explicit status", () => {
  const backup = validBackup();
  delete backup.state.tickets[0].done;
  assert.match(validateBackupDocument(backup).error, /invalid kitchen ticket/);
});

test("rejects kitchen tickets with an invalid table", () => {
  const backup = validBackup();
  backup.state.tickets[0].table = 0;
  assert.match(validateBackupDocument(backup).error, /invalid kitchen ticket/);
});


test("rejects a sale total that does not equal its line items", () => {
  const backup = validBackup();
  backup.state.sales[0].total = 246;
  assert.match(validateBackupDocument(backup).error, /total does not match/);
});

test("rejects completed sales with no line items", () => {
  const backup = validBackup();
  backup.state.sales[0].items = [];
  assert.match(validateBackupDocument(backup).error, /invalid sale record/);
});


test("accepts optional stock adjustment history and keeps old backup compatibility", () => {
  const backup = validBackup();
  assert.equal(validateBackupDocument(backup).ok, true);
  delete backup.state.stockAdjustments;
  assert.equal(validateBackupDocument(backup).ok, true);
});

test("rejects stock adjustment entries with inconsistent quantities", () => {
  const backup = validBackup();
  backup.state.stockAdjustments[0].delta = 1;
  assert.match(validateBackupDocument(backup).error, /invalid stock adjustment history/);
});

test("rejects duplicate stock adjustment IDs", () => {
  const backup = validBackup();
  backup.state.stockAdjustments.push({ ...backup.state.stockAdjustments[0] });
  assert.match(validateBackupDocument(backup).error, /invalid stock adjustment history/);
});


test("rejects table occupancy that points to no saved sale", () => {
  const backup = validBackup();
  backup.state.tables["2"] = 9999;
  assert.match(validateBackupDocument(backup).error, /table occupancy/);
});

test("rejects table occupancy for a different table than the sale", () => {
  const backup = validBackup();
  backup.state.tables["3"] = 1001;
  delete backup.state.tables["2"];
  assert.match(validateBackupDocument(backup).error, /table occupancy/);
});

test("rejects table occupancy outside the configured table range", () => {
  const backup = validBackup();
  backup.state.tables["13"] = 1001;
  assert.match(validateBackupDocument(backup).error, /table occupancy/);
});


test("accepts legacy backups without a saved draft cart", () => {
  const backup = validBackup();
  delete backup.state.openCart;
  delete backup.state.orderType;
  delete backup.state.tableNo;
  delete backup.state.payment;
  delete backup.state.customer;
  assert.equal(validateBackupDocument(backup).ok, true);
});

test("rejects a saved cart line for a nonexistent menu item", () => {
  const backup = validBackup();
  backup.state.openCart = [{ id: 9999, qty: 1 }];
  assert.match(validateBackupDocument(backup).error, /invalid open cart/);
});

test("rejects duplicate or invalid draft quantities", () => {
  const backup = validBackup();
  backup.state.openCart = [{ id: 1, qty: 1 }, { id: 1, qty: 2 }];
  assert.match(validateBackupDocument(backup).error, /invalid open cart/);
  backup.state.openCart = [{ id: 1, qty: 0 }];
  assert.match(validateBackupDocument(backup).error, /invalid open cart/);
});

test("rejects invalid saved checkout context", () => {
  const backup = validBackup();
  backup.state.tableNo = 13;
  assert.match(validateBackupDocument(backup).error, /invalid checkout context/);
});
