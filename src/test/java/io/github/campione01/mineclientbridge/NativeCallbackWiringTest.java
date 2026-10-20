package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeCallbackWiringTest {
    private static final String MIXIN_SOURCES = "src/main/java/io/github/campione01/mineclientbridge/mixin/";

    @Test
    void mixinConfigRequiresWindowMixinAlongsideTheInputMixins() throws Exception {
        JsonObject config = JsonParser.parseString(read("src/main/resources/mineclient_bridge.mixins.json"))
                .getAsJsonObject();
        assertTrue(config.get("required").getAsBoolean());
        assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
        List<String> client = new ArrayList<>();
        config.getAsJsonArray("client").forEach(name -> client.add(name.getAsString()));
        assertTrue(client.containsAll(List.of(
                "InputConstantsMixin", "MouseHandlerMixin", "KeyboardHandlerMixin", "WindowMixin")));
        assertEquals(client.size(), client.stream().distinct().count());
    }

    @Test
    void isolatedCallbackSetupInstallsPlaceholdersBeforeCancellingVanillaRegistration() throws Exception {
        String source = read(MIXIN_SOURCES + "InputConstantsMixin.java");
        assertPlaceholdersBeforeCancel(injection(source,
                "@Inject(method = \"setupKeyboardCallbacks\", at = @At(\"HEAD\"), cancellable = true)"),
                "IsolatedNativeCallbacks.ensureKeyboard(window);");
        assertPlaceholdersBeforeCancel(injection(source,
                "@Inject(method = \"setupMouseCallbacks\", at = @At(\"HEAD\"), cancellable = true)"),
                "IsolatedNativeCallbacks.ensureMouse(window);");
    }

    @Test
    void windowCloseDetachesPlaceholdersAtHeadAndReleasesThemAtReturn() throws Exception {
        String source = read(MIXIN_SOURCES + "WindowMixin.java");
        assertTrue(source.contains("@Mixin(Window.class)"));
        assertEquals(2, occurrences(source, "@Inject("));
        String head = injection(source, "@Inject(method = \"close\", at = @At(\"HEAD\"))");
        assertTrue(head.contains("IsolatedNativeCallbacks.prepareClose(((Window) (Object) this).getWindow());"));
        assertFalse(head.contains("IsolatedNativeCallbacks.release("));
        String tail = injection(source, "@Inject(method = \"close\", at = @At(\"RETURN\"))");
        assertTrue(tail.contains("IsolatedNativeCallbacks.release(((Window) (Object) this).getWindow());"));
        assertFalse(tail.contains("prepareClose"));
    }

    private static void assertPlaceholdersBeforeCancel(String injection, String ensure) {
        int guard = injection.indexOf("if (ClientInputIsolation.suppressNativeCallbackRegistration()) {");
        int placeholders = injection.indexOf(ensure);
        int cancel = injection.indexOf("callback.cancel();");
        assertTrue(guard >= 0, "isolation guard");
        assertTrue(guard < placeholders, "placeholders are installed inside the isolation guard");
        assertTrue(placeholders < cancel, "placeholders are installed before vanilla registration is cancelled");
        assertEquals(1, occurrences(injection, "callback.cancel();"));
    }

    private static String injection(String source, String annotation) {
        assertEquals(1, occurrences(source, annotation), annotation);
        int start = source.indexOf(annotation);
        int next = source.indexOf("@Inject(", start + annotation.length());
        return source.substring(start, next < 0 ? source.length() : next);
    }

    private static String read(String relative) throws Exception {
        Path project = Path.of(System.getProperty("mineclientBridge.projectDir"));
        return Files.readString(project.resolve(relative));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int position = 0;
        while ((position = source.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }
}
