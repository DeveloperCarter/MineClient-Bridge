package io.github.campione01.mineclientbridge;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** [Arcane Academy fork] Identity-based bounded deduplication for lines that bypass the chat event. */
final class ChatFeedbackTracker {
    private static final int LIMIT = 256;
    private final IdentityHashMap<Object, Boolean> received = new IdentityHashMap<>();
    private final ArrayDeque<Object> receivedOrder = new ArrayDeque<>();
    private final IdentityHashMap<Object, Boolean> lines = new IdentityHashMap<>();
    private final ArrayDeque<Object> lineOrder = new ArrayDeque<>();

    void received(Object component) { remember(received, receivedOrder, component); }
    boolean local(Object line, Object component) {
        if (lines.containsKey(line)) return false;
        remember(lines, lineOrder, line);
        return !received.containsKey(component);
    }
    private static void remember(IdentityHashMap<Object, Boolean> seen, ArrayDeque<Object> order, Object value) {
        if (seen.put(value, true) != null) return;
        order.addLast(value);
        if (order.size() > LIMIT) seen.remove(order.removeFirst());
    }
    void clear() { received.clear(); receivedOrder.clear(); lines.clear(); lineOrder.clear(); }
}
