package com.musicone.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipFile;

/** 仅补充迁包后仍有精确字节码身份的历史规则，不生成新的运行覆盖证据。 */
public abstract class ProfileNamespaceCompatibilityTask extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getOriginalProfiles();
    @Classpath public abstract ConfigurableFileCollection getCompiledClasses();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    @TaskAction public void generate() throws IOException {
        Map<String, Set<String>> classes = new HashMap<>();
        for (File file : getCompiledClasses()) index(file, classes);
        File output = getOutputDirectory().get().getAsFile();
        Files.createDirectories(output.toPath());
        for (String name : List.of("baseline-prof.txt", "startup-prof.txt")) {
            List<String> originals = new ArrayList<>();
            for (File root : getOriginalProfiles()) {
                File source = root.isDirectory() ? new File(root, name) : root;
                if (source.isFile() && source.getName().equals(name)) originals.addAll(Files.readAllLines(source.toPath()));
            }
            SortedMap<String, String> additions = remap(originals, classes);
            List<String> lines = additions.entrySet().stream().map(e -> e.getValue() + e.getKey()).toList();
            Files.write(new File(output, name).toPath(), lines, StandardCharsets.UTF_8);
            long historical = originals.stream().filter(s -> s.contains("Lcom/musicone/demo/")).count();
            getLogger().lifecycle("{}：历史旧包名规则 {} 条，当前字节码精确匹配后补充 {} 条；不代表新增采集覆盖", name, historical, lines.size());
        }
    }

    static SortedMap<String, String> remap(List<String> originals, Map<String, Set<String>> classes) {
        SortedMap<String, String> result = new TreeMap<>();
        for (String raw : originals) {
            String line = raw.trim();
            int start = line.indexOf('L');
            if (start < 0 || !line.contains("Lcom/musicone/demo/")) continue;
            String flags = line.substring(0, start);
            if (!flags.matches("[HSP]*")) continue;
            String mapped = line.substring(start).replace("Lcom/musicone/demo/", "Lcom/musicone/");
            int end = mapped.indexOf(';');
            if (end < 0) continue;
            String owner = mapped.substring(1, end);
            Set<String> methods = classes.get(owner);
            if (methods == null) continue;
            String tail = mapped.substring(end + 1);
            // 合成方法也必须逐个核对名称与完整参数/返回值，不能凭类名命中制造方法规则。
            if (!tail.isEmpty() && (!tail.startsWith("->") || !methods.contains(tail.substring(2)))) continue;
            result.merge(mapped, flags, ProfileNamespaceCompatibilityTask::unionFlags);
        }
        return result;
    }

    static String unionFlags(String left, String right) {
        StringBuilder result = new StringBuilder();
        for (char flag : "HSP".toCharArray()) if (left.indexOf(flag) >= 0 || right.indexOf(flag) >= 0) result.append(flag);
        return result.toString();
    }

    private static void index(File input, Map<String, Set<String>> classes) throws IOException {
        if (input.isDirectory()) {
            try (var paths = Files.walk(input.toPath())) {
                for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                    try (InputStream stream = Files.newInputStream(path)) { readClass(stream, classes); }
                }
            }
        } else if (input.isFile()) {
            try (ZipFile zip = new ZipFile(input)) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                        try (InputStream stream = zip.getInputStream(entry)) { readClass(stream, classes); }
                    }
                }
            }
        }
    }

    static void readClass(InputStream stream, Map<String, Set<String>> classes) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("Profile 兼容输入不是 class 文件");
        in.skipNBytes(4);
        Object[] pool = new Object[in.readUnsignedShort()];
        for (int i = 1; i < pool.length; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 7 -> pool[i] = in.readUnsignedShort();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> { in.skipNBytes(8); i++; }
                case 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("不支持的 class 常量标记：" + tag);
            }
        }
        in.readUnsignedShort();
        String owner = (String) pool[(Integer) pool[in.readUnsignedShort()]];
        in.readUnsignedShort();
        in.skipNBytes(in.readUnsignedShort() * 2L);
        for (int count = in.readUnsignedShort(); count > 0; count--) { in.skipNBytes(6); skipAttributes(in); }
        Set<String> methods = classes.computeIfAbsent(owner, ignored -> new HashSet<>());
        for (int count = in.readUnsignedShort(); count > 0; count--) {
            in.readUnsignedShort();
            String name = (String) pool[in.readUnsignedShort()];
            String descriptor = (String) pool[in.readUnsignedShort()];
            methods.add(name + descriptor);
            skipAttributes(in);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        for (int count = in.readUnsignedShort(); count > 0; count--) {
            in.readUnsignedShort();
            in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
        }
    }
}
