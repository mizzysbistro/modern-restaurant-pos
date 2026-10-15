package com.mizzysbistro.pos;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class CustomerDisplayStateTest {
    @Test
    public void acceptsAndSummarizesBoundedDisplayPayload() {
        String json = "{\"orderType\":\"Dine in\",\"tableNo\":4,\"customer\":\"Walk-in\"," +
            "\"message\":\"Please review your order\",\"items\":[{\"name\":\"Chicken Plate\",\"qty\":2,\"price\":245}]," +
            "\"total\":490}";
        CustomerDisplayState state = CustomerDisplayState.parse(json);
        assertNotNull(state);
        assertEquals("Table 04", state.location);
        assertEquals(1, state.items.size());
        assertEquals("Chicken Plate", state.items.get(0).name);
        assertEquals(490d, state.total, 0.001d);
    }

    @Test
    public void rejectsTotalsThatDoNotMatchLineItems() {
        String json = "{\"orderType\":\"Takeaway\",\"items\":[{\"name\":\"Dish\",\"qty\":2,\"price\":10}]," +
            "\"total\":19}";
        assertNull(CustomerDisplayState.parse(json));
    }

    @Test
    public void rejectsUnsafeItemAndContextValues() {
        assertNull(CustomerDisplayState.parse("{\"orderType\":\"Dine in\",\"tableNo\":13,\"items\":[],\"total\":0}"));
        assertNull(CustomerDisplayState.parse("{\"orderType\":\"Takeaway\",\"items\":[{\"name\":\" \",\"qty\":1,\"price\":1}],\"total\":1}"));
        assertNull(CustomerDisplayState.parse("{\"orderType\":\"Takeaway\",\"items\":[],\"total\":-1}"));
    }

    @Test
    public void rejectsMalformedAndOversizedPayloads() {
        assertNull(CustomerDisplayState.parse("not json"));
        assertNull(CustomerDisplayState.parse(new String(new char[CustomerDisplayState.MAX_JSON_CHARS + 1]).replace('\0', 'x')));
    }
}
