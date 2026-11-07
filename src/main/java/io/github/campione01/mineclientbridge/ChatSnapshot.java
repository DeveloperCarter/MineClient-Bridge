package io.github.campione01.mineclientbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;

/**
 * Reads what the player can currently read as text: the recent chat history and the action-bar
 * message above the hotbar. Without it, an agent has to read command output and status messages
 * from screenshots, where open chat lines can cover the action bar.
 *
 * <p>Read-only. Both values are private in vanilla, so the access transformer opens
 * {@code ChatComponent.allMessages}, {@code Gui.overlayMessageString} and
 * {@code Gui.overlayMessageTime}; renamed fields on a Minecraft update break the build, not the
 * game. Chat can hold other players' messages, so this belongs on development clients only, like
 * the rest of the bridge.</p>
 *
 * <p>Must run on the Minecraft thread.</p>
 */
final class ChatSnapshot {
    static final int DEFAULT_MESSAGES = 20;
    static final int MAX_MESSAGES = 100;
    private static final int MAX_MESSAGE_CHARACTERS = 1024;

    private ChatSnapshot() {
    }

    static JsonObject create(int limit) {
        Minecraft mc = Minecraft.getInstance();
        Gui gui = mc.gui;
        int guiTick = gui.getGuiTicks();

        JsonObject obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.addProperty("gui_tick", guiTick);

        JsonObject actionBar = new JsonObject();
        Component overlay = gui.overlayMessageString;
        boolean showing = overlay != null && gui.overlayMessageTime > 0;
        actionBar.addProperty("showing", showing);
        if (overlay != null) {
            actionBar.addProperty("text", bounded(overlay.getString()));
            actionBar.addProperty("remaining_ticks", Math.max(0, gui.overlayMessageTime));
        }
        obj.add("action_bar", actionBar);

        // Newest first, as ChatComponent stores them; it keeps at most 100.
        List<GuiMessage> messages = gui.getChat().allMessages;
        int returned = Math.min(Math.min(limit, MAX_MESSAGES), messages.size());
        JsonArray entries = new JsonArray();
        for (int i = 0; i < returned; i++) {
            GuiMessage message = messages.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("text", bounded(message.content().getString()));
            entry.addProperty("added_tick", message.addedTime());
            entry.addProperty("age_ticks", guiTick - message.addedTime());
            if (message.tag() != null && message.tag().logTag() != null) {
                entry.addProperty("tag", message.tag().logTag());
            }
            entries.add(entry);
        }
        obj.addProperty("total", messages.size());
        obj.addProperty("returned", returned);
        obj.add("messages", entries);
        return obj;
    }

    private static String bounded(String value) {
        if (value.length() <= MAX_MESSAGE_CHARACTERS) {
            return value;
        }
        return value.substring(0, MAX_MESSAGE_CHARACTERS);
    }
}
