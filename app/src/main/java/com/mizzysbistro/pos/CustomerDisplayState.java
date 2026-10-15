package com.mizzysbistro.pos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded, validated content for a customer-facing display; never accepts HTML. */
final class CustomerDisplayState {
    static final int MAX_JSON_CHARS = 128 * 1024;
    static final int MAX_ITEMS = 100;
    final String message;
    final String location;
    final String customer;
    final List<Item> items;
    final double total;

    static final class Item {
        final String name;
        final int quantity;
        final double price;

        Item(String name, int quantity, double price) {
            this.name = name;
            this.quantity = quantity;
            this.price = price;
        }
    }

    private CustomerDisplayState(String message, String location, String customer,
                                 List<Item> items, double total) {
        this.message = message;
        this.location = location;
        this.customer = customer;
        this.items = Collections.unmodifiableList(items);
        this.total = total;
    }

    static CustomerDisplayState parse(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_CHARS) return null;
        try {
            JSONObject root = new JSONObject(json);
            String orderType = root.optString("orderType", "");
            if (!"Dine in".equals(orderType) && !"Takeaway".equals(orderType)) return null;
            String customer = root.optString("customer", "");
            String message = root.optString("message", "Welcome to Mizzy's Bistro");
            if (customer.length() > 200 || message.length() > 200) return null;

            String location;
            if ("Dine in".equals(orderType)) {
                Object rawTable = root.opt("tableNo");
                if (!(rawTable instanceof Number)) return null;
                double table = ((Number) rawTable).doubleValue();
                if (!Double.isFinite(table) || table != Math.rint(table) || table < 1 || table > 12) return null;
                location = "Table " + String.format(java.util.Locale.ROOT, "%02d", (int) table);
            } else {
                location = "Takeaway";
            }

            Object rawTotal = root.opt("total");
            JSONArray rawItems = root.optJSONArray("items");
            if (!(rawTotal instanceof Number) || rawItems == null || rawItems.length() > MAX_ITEMS) return null;
            double total = ((Number) rawTotal).doubleValue();
            if (!Double.isFinite(total) || total < 0 || total > 1_000_000_000d) return null;
            List<Item> items = new ArrayList<>();
            double calculated = 0;
            for (int i = 0; i < rawItems.length(); i++) {
                JSONObject item = rawItems.optJSONObject(i);
                if (item == null) return null;
                String name = item.optString("name", "");
                Object rawQuantity = item.opt("qty");
                Object rawPrice = item.opt("price");
                if (name.trim().isEmpty() || name.length() > 200 ||
                    !(rawQuantity instanceof Number) || !(rawPrice instanceof Number)) return null;
                double rawQty = ((Number) rawQuantity).doubleValue();
                double price = ((Number) rawPrice).doubleValue();
                if (!Double.isFinite(rawQty) || rawQty != Math.rint(rawQty) || rawQty < 1 || rawQty > 100000 ||
                    !Double.isFinite(price) || price < 0 || price > 100_000_000d) return null;
                int quantity = (int) rawQty;
                calculated += price * quantity;
                if (!Double.isFinite(calculated) || calculated > 1_000_000_000d) return null;
                items.add(new Item(name, quantity, price));
            }
            if (Math.abs(calculated - total) > 0.02d) return null;
            return new CustomerDisplayState(message, location, customer, items, total);
        } catch (JSONException | RuntimeException error) {
            return null;
        }
    }
}
