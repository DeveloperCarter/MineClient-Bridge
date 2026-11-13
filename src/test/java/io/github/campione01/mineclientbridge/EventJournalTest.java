package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventJournalTest {
    @BeforeEach
    void reset() {
        EventJournal.tickSource = () -> 7;
        EventJournal.clearForTest();
    }

    private static void add(String type, String text) {
        JsonObject data = new JsonObject();
        data.addProperty("text", text);
        EventJournal.record(type, data);
    }

    @Test
    void cursorReadsEachEventOnce() {
        add("chat", "one");
        add("sound", "two");
        JsonObject first = EventJournal.read(0, Set.of(), 100);
        assertEquals(2, first.getAsJsonArray("events").size());
        assertEquals(2, first.get("next").getAsLong());
        assertEquals(7, first.getAsJsonArray("events").get(0).getAsJsonObject().get("game_tick").getAsLong());
        add("chat", "three");
        JsonObject second = EventJournal.read(first.get("next").getAsLong(), Set.of(), 100);
        assertEquals(1, second.getAsJsonArray("events").size());
        assertEquals("three", second.getAsJsonArray("events").get(0).getAsJsonObject()
                .getAsJsonObject("data").get("text").getAsString());
    }

    @Test
    void typeFilterStillAdvancesTheCursor() {
        add("chat", "a");
        add("sound", "b");
        add("chat", "c");
        JsonObject read = EventJournal.read(0, Set.of("sound"), 100);
        assertEquals(1, read.getAsJsonArray("events").size());
        assertEquals(3, read.get("next").getAsLong(), "skipped types are not read again");
    }

    @Test
    void aCursorPastTheEndReturnsTheNewestSequence() {
        add("chat", "old");
        JsonObject head = EventJournal.read(Long.MAX_VALUE, Set.of(), 1);
        assertEquals(0, head.getAsJsonArray("events").size());
        assertEquals(1, head.get("next").getAsLong());
        add("chat", "new");
        assertEquals("new", EventJournal.read(1, Set.of(), 10).getAsJsonArray("events").get(0).getAsJsonObject()
                .getAsJsonObject("data").get("text").getAsString());
    }

    @Test
    void limitLeavesTheRestForTheNextRead() {
        for (int i = 0; i < 5; i++) add("chat", "m" + i);
        JsonObject read = EventJournal.read(0, Set.of(), 2);
        assertEquals(2, read.getAsJsonArray("events").size());
        assertTrue(read.get("more").getAsBoolean());
        assertEquals(2, read.get("next").getAsLong());
    }

    @Test
    void overflowDropsTheOldestAndReportsMissed() {
        for (int i = 0; i < EventJournal.CAPACITY + 3; i++) add("chat", "m" + i);
        JsonObject read = EventJournal.read(0, Set.of(), 1);
        assertEquals(3, read.get("dropped_total").getAsLong());
        assertEquals(3, read.get("missed").getAsLong());
    }

    @Test
    void externalEventsKeepTheirJsonOrText() {
        EventJournal.recordExternal("arcaneacademy:player_death", "{\"cause\":\"minecraft:fall\"}");
        EventJournal.recordExternal("arcaneacademy:odd", "not json {");
        var events = EventJournal.read(0, Set.of(), 10).getAsJsonArray("events");
        assertEquals("minecraft:fall", events.get(0).getAsJsonObject().getAsJsonObject("data").get("cause").getAsString());
        assertEquals("not json {", events.get(1).getAsJsonObject().getAsJsonObject("data").get("text").getAsString());
    }
}
