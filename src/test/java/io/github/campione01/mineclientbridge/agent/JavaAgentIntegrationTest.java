package io.github.campione01.mineclientbridge.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** [Arcane Academy fork] Real JVM refusal/atomicity and system-loader holder, without Minecraft. */
class JavaAgentIntegrationTest {
    @TempDir Path root;

    @Test void actualAgentSwapsBodiesRetainsStaticsAndRefusesSchemaChangesAtomically() throws Exception {
        Path source = root.resolve("Probe.java");
        Files.writeString(source, """
                import java.nio.file.*;
                import java.util.*;
                import javax.tools.ToolProvider;
                public class Probe {
                    static Path root;
                    static void compile(String name, String fields, int value) throws Exception {
                        Path source = root.resolve(name + ".java");
                        Files.writeString(source, "package spike.fixture; public class " + name + " { " + fields
                            + " public static int value() { return " + value + "; } }");
                        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", root.toString(), source.toString()) != 0)
                            throw new AssertionError("compile");
                    }
                    static Map<?,?> swap() throws Exception {
                        Class<?> holder = Class.forName("io.github.campione01.mineclientbridge.agent.AgentHolder", true, ClassLoader.getSystemClassLoader());
                        return (Map<?,?>) holder.getMethod("hotswap").invoke(null);
                    }
                    static int value(Class<?> type) throws Exception { return (int) type.getMethod("value").invoke(null); }
                    public static void main(String[] args) throws Exception {
                        root = Path.of(args[0]);
                        compile("Target", "public static int state = 7;", 1);
                        compile("Other", "", 10);
                        Class<?> target = Class.forName("spike.fixture.Target");
                        Class<?> other = Class.forName("spike.fixture.Other");
                        if (value(target) != 1 || value(other) != 10) throw new AssertionError("baseline");
                        compile("Target", "public static int state = 42;", 2);
                        Map<?,?> body = swap();
                        if (value(target) != 2 || target.getField("state").getInt(null) != 7
                            || ((List<?>) body.get("swapped")).size() != 1) throw new AssertionError("body/static: " + body);
                        compile("Target", "public static int state = 42; private int added;", 3);
                        compile("Other", "", 11);
                        Map<?,?> refused = swap();
                        if (!Boolean.TRUE.equals(refused.get("restart_required")) || ((List<?>)refused.get("refused")).size() != 2
                            || !refused.toString().contains("UnsupportedOperationException") || value(target) != 2 || value(other) != 10)
                            throw new AssertionError("atomic refusal: " + refused);
                        compile("Target", "public static int state = 42;", 3);
                        Map<?,?> recovered = swap();
                        if (value(target) != 3 || value(other) != 11 || ((List<?>)recovered.get("swapped")).size() != 2)
                            throw new AssertionError("pending hashes: " + recovered);
                        compile("Lazy", "", 99);
                        Map<?,?> lazy = swap();
                        if (!((List<?>)lazy.get("new_on_disk")).contains("spike.fixture.Lazy")) throw new AssertionError("lazy: " + lazy);
                        if (value(Class.forName("spike.fixture.Lazy")) != 99) throw new AssertionError("first load");
                        compile("Transformed", "", 20);
                        Path transformedFile = root.resolve("spike/fixture/Transformed.class");
                        byte[] raw = Files.readAllBytes(transformedFile);
                        compile("Transformed", "", 21);
                        byte[] loaderBytes = Files.readAllBytes(transformedFile);
                        Files.write(transformedFile, raw);
                        Class<?> transformed = new ClassLoader(null) {
                            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                                if (!name.equals("spike.fixture.Transformed")) throw new ClassNotFoundException(name);
                                return defineClass(name, loaderBytes, 0, loaderBytes.length);
                            }
                        }.loadClass("spike.fixture.Transformed");
                        Map<?,?> skipped = swap();
                        if (!((List<?>)skipped.get("transformed")).contains("spike.fixture.Transformed")
                            || !Boolean.TRUE.equals(skipped.get("restart_required")) || value(transformed) != 21)
                            throw new AssertionError("loader transform was overwritten: " + skipped);
                        System.out.println("REAL_AGENT_OK body=swapped statics=retained batch=atomic schema=refused lazy=normal transformed=skipped");
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", root.toString(), source.toString()));
        Path output = root.resolve("output.txt");
        String javaCommand = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", javaCommand).toString(),
                "-javaagent:" + System.getProperty("mineclientBridge.agentJar"),
                "-DmineclientBridge.hotswapRoots=" + root,
                "-DmineclientBridge.hotswapPackages=spike.fixture",
                "-cp", root.toString(), "Probe", root.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "agent fixture timed out");
            String log = Files.readString(output);
            assertEquals(0, process.exitValue(), log);
            assertTrue(log.contains("REAL_AGENT_OK"), log);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
