package io.github.campione01.mineclientbridge.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** [Arcane Academy fork] Change detection and atomic reports, deliberately free of game classes. */
public final class HotSwapEngine {
    public static final String STATIC_FIELDS_NOTE =
            "Static fields keep their values; static initializers do not run again. Registrations need a restart.";
    private final Instrumentation instrumentation;
    private final List<Path> roots;
    private final List<String> packages;
    private final Map<DefinitionKey, Baseline> definitions = new ConcurrentHashMap<>();
    private final Map<DefinitionKey, String> lastSwapped = new HashMap<>();

    public HotSwapEngine(Instrumentation instrumentation, List<Path> roots, List<String> packages) {
        this.instrumentation = instrumentation;
        this.roots = roots.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
        this.packages = packages.stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.endsWith(".") ? s : s + ".").toList();
    }

    public static HotSwapEngine fromSystemProperties(Instrumentation instrumentation) {
        String roots = System.getProperty("mineclientBridge.hotswapRoots", "");
        String packages = System.getProperty("mineclientBridge.hotswapPackages", "");
        return new HotSwapEngine(instrumentation,
                Arrays.stream(roots.split(Pattern.quote(File.pathSeparator)))
                        .filter(s -> !s.isBlank()).map(Path::of).toList(),
                Arrays.asList(packages.split(",")));
    }

    public boolean allowed(String name) {
        // Even an accidentally broad allowlist must never target the game, loader or bridge.
        if (List.of("java.", "javax.", "jdk.", "sun.", "net.minecraft.", "net.neoforged.",
                "net.minecraftforge.", "cpw.mods.", "io.github.campione01.mineclientbridge.")
                .stream().anyMatch(name::startsWith)) return false;
        return packages.stream().anyMatch(name::startsWith);
    }

    public void recordDefinition(ClassLoader loader, String name, byte[] definedBytes) {
        if (!allowed(name)) return;
        try {
            Path disk = classFile(name);
            String rawHash = disk == null ? null : hash(Files.readAllBytes(disk));
            String definedHash = hash(definedBytes);
            definitions.putIfAbsent(new DefinitionKey(loader, name),
                    new Baseline(rawHash, definedHash, !definedHash.equals(rawHash)));
        } catch (IOException e) {
            // Unknown startup bytes are not safe to redefine later. Fail closed per class.
            definitions.putIfAbsent(new DefinitionKey(loader, name), new Baseline(null, null, true));
        }
    }

    public synchronized Map<String, Object> swap() {
        List<String> swapped = new ArrayList<>();
        List<Map<String, String>> refused = new ArrayList<>();
        List<String> transformed = new ArrayList<>();
        List<String> newOnDisk = new ArrayList<>();
        List<ClassDefinition> batch = new ArrayList<>();
        Map<DefinitionKey, String> updates = new HashMap<>();
        Set<String> loadedNames = new HashSet<>();
        int unchanged = 0;
        int hidden = 0;

        for (Class<?> type : Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(c -> allowed(c.getName())).sorted(Comparator.comparing(Class::getName)).toList()) {
            // Lambda implementation classes are JVM-generated hidden classes, not compiler
            // output files. They have no ClassFileTransformer baseline and cannot be redefined;
            // do not misreport them as loader-transformed mod classes requiring a restart.
            if (type.isHidden()) {
                hidden++;
                continue;
            }
            String name = type.getName();
            loadedNames.add(name);
            DefinitionKey key = new DefinitionKey(type.getClassLoader(), name);
            Baseline baseline = definitions.get(key);
            if (baseline == null || baseline.transformed()) {
                transformed.add(name);
                continue;
            }
            try {
                Path file = classFile(name);
                if (file == null) throw new IOException("class file missing from configured roots");
                byte[] bytes = Files.readAllBytes(file);
                String hash = hash(bytes);
                if (hash.equals(lastSwapped.getOrDefault(key, baseline.diskHash()))) {
                    unchanged++;
                } else {
                    batch.add(new ClassDefinition(type, bytes));
                    updates.put(key, hash);
                }
            } catch (IOException e) {
                refused.add(refusal(name, e + ": needs a restart"));
            }
        }

        try {
            for (Path root : roots) {
                if (!Files.isDirectory(root)) continue;
                try (var files = Files.walk(root)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                        String relative = root.relativize(file).toString().replace(File.separatorChar, '.');
                        String name = relative.substring(0, relative.length() - 6);
                        if (allowed(name) && !loadedNames.contains(name) && !newOnDisk.contains(name)) newOnDisk.add(name);
                    }
                }
            }
        } catch (IOException e) {
            refused.add(refusal("<class roots>", e + ": needs a restart"));
        }
        newOnDisk.sort(String::compareTo);

        // One JVM call is atomic. Never retry subsets: after a refusal the complete old batch
        // remains installed, and its disk hashes must stay pending for the next attempt.
        if (!batch.isEmpty()) {
            if (!refused.isEmpty()) {
                for (ClassDefinition definition : batch) refused.add(refusal(definition.getDefinitionClass().getName(),
                        "Batch not attempted because class-file reading failed; needs a restart"));
            } else {
                try {
                    instrumentation.redefineClasses(batch.toArray(ClassDefinition[]::new));
                    lastSwapped.putAll(updates);
                    for (ClassDefinition definition : batch) swapped.add(definition.getDefinitionClass().getName());
                } catch (Exception | LinkageError e) {
                    for (ClassDefinition definition : batch) refused.add(refusal(definition.getDefinitionClass().getName(),
                            e.getClass().getSimpleName() + ": " + e.getMessage() + "; needs a restart (entire batch unchanged)"));
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", true);
        report.put("swapped", swapped);
        report.put("refused", refused);
        report.put("transformed", transformed);
        report.put("new_on_disk", newOnDisk);
        report.put("unchanged_count", unchanged);
        report.put("skipped_hidden_count", hidden);
        report.put("restart_required", !refused.isEmpty() || !transformed.isEmpty());
        report.put("static_fields_note", STATIC_FIELDS_NOTE);
        return report;
    }

    private Path classFile(String name) throws IOException {
        Path found = null;
        for (Path root : roots) {
            Path candidate = root.resolve(name.replace('.', File.separatorChar) + ".class").normalize();
            if (!candidate.startsWith(root)) throw new IOException("class path escaped root");
            if (Files.isRegularFile(candidate)) {
                if (found != null) throw new IOException("duplicate class output: " + name);
                found = candidate;
            }
        }
        return found;
    }

    private static Map<String, String> refusal(String name, String reason) {
        return Map.of("class", name, "reason", reason);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Baseline(String diskHash, String definedHash, boolean transformed) { }

    // Class names can occur in multiple loaders; compare the loader by identity, not its equals.
    private record DefinitionKey(ClassLoader loader, String name) {
        @Override public boolean equals(Object other) {
            return other instanceof DefinitionKey k && loader == k.loader && name.equals(k.name);
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(loader) + name.hashCode(); }
    }
}
