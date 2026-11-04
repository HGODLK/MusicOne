package com.musicone.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;
import javax.inject.Inject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

/** 先把默认接口 companion 物化为普通 classfile，再让共享 ABI 的 keep 规则参与 R8。 */
public abstract class SharedInterfaceDesugarTask extends DefaultTask {
    @InputFiles public abstract ListProperty<RegularFile> getAllJars();
    @InputFiles public abstract ListProperty<Directory> getAllDirectories();
    @Classpath public abstract ConfigurableFileCollection getBootClasspath();
    @Classpath public abstract ConfigurableFileCollection getCompilerClasspath();
    @Input public abstract Property<Integer> getWorkers();
    @OutputFile public abstract RegularFileProperty getOutput();
    @Inject protected abstract ExecOperations getExec();

    @TaskAction public void desugar() throws IOException {
        File input = new File(getTemporaryDir(), "classes.jar");
        Set<String> names = new HashSet<>();
        try (JarOutputStream merged = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(input)))) {
            for (RegularFile file : getAllJars().get()) {
                try (ZipFile jar = new ZipFile(file.getAsFile())) {
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (!entry.isDirectory() && entry.getName().endsWith(".class") && names.add(entry.getName())) {
                            try (InputStream bytes = jar.getInputStream(entry)) { add(merged, entry.getName(), bytes); }
                        }
                    }
                }
            }
            for (Directory directory : getAllDirectories().get()) {
                var root = directory.getAsFile().toPath();
                try (var paths = Files.walk(root)) {
                    for (var path : paths.filter(Files::isRegularFile).toList()) {
                        String name = root.relativize(path).toString().replace(File.separatorChar, '/');
                        if (name.endsWith(".class") && names.add(name)) {
                            try (InputStream bytes = Files.newInputStream(path)) { add(merged, name, bytes); }
                        }
                    }
                }
            }
        }
        File target = getOutput().get().getAsFile();
        Files.createDirectories(target.toPath().getParent());
        List<String> arguments = new ArrayList<>(List.of("--classfile", "--release", "--min-api", "23",
            "--thread-count", getWorkers().get().toString(), "--output", target.getAbsolutePath()));
        for (File boot : getBootClasspath().getFiles()) { arguments.add("--lib"); arguments.add(boot.getAbsolutePath()); }
        arguments.add(input.getAbsolutePath());
        getExec().javaexec(spec -> {
            spec.setClasspath(getCompilerClasspath());
            spec.getMainClass().set("com.android.tools.r8.D8");
            spec.setMaxHeapSize("8g");
            spec.args(arguments);
        }).assertNormalExitValue();
    }

    private static void add(JarOutputStream output, String name, InputStream input) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        input.transferTo(output);
        output.closeEntry();
    }
}
