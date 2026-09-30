import com.android.tools.smali.baksmali.Baksmali;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Development aid, run with the ReVanced CLI jar on the class path:
 * {@code java -cp tools/revanced-cli-6.0.0-all.jar scripts/Inspect.java <apk> <out dir> [class ...]}.
 * Prints the hook table a patched APK carries and disassembles the named classes (dotted names) to smali.
 */
public final class Inspect {
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]); File out = new File(args[1]);
        Set<String> wanted = new HashSet<>();
        for (String name : Arrays.copyOfRange(args, 2, args.length)) wanted.add("L" + name.replace('.', '/') + ";");
        // Read file by file, first definition wins, as the runtime does: the patcher leaves the original's highest-numbered dex
        // files in place when the rewritten code needs fewer files, and those repeat classes of the earlier ones.
        java.util.LinkedHashMap<String, ClassDef> byType = new java.util.LinkedHashMap<>(); int stale = 0;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            for (int i = 1; ; i++) {
                java.util.zip.ZipEntry entry = zip.getEntry(i == 1 ? "classes.dex" : "classes" + i + ".dex");
                if (entry == null) break;
                byte[] bytes; try (java.io.InputStream in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
                for (ClassDef type : new com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile(null, bytes).getClasses())
                    if (byType.putIfAbsent(type.getType(), type) != null) stale++;
            }
        }
        System.out.println("shadowed duplicates in later dex files: " + stale);
        DexFile dex = new DexFile() {
            @Override public Set<? extends ClassDef> getClasses() { return new java.util.LinkedHashSet<>(byType.values()); }
            @Override public Opcodes getOpcodes() { return Opcodes.getDefault(); }
        };
        List<String> classes = new ArrayList<>();
        int total = 0;
        for (ClassDef type : dex.getClasses()) {
            total++;
            if (type.getType().equals("Ldev/ichinomiya/ninebotenhance/embedded/HookTable;")) for (Field field : type.getStaticFields()) {
                if (!field.getName().equals("TABLE") || !(field.getInitialValue() instanceof StringEncodedValue)) continue;
                String[] lines = ((StringEncodedValue) field.getInitialValue()).getValue().split("\n");
                Set<String> owners = new HashSet<>(); int constructors = 0;
                for (String line : lines) { owners.add(line.substring(0, line.indexOf("->"))); if (line.contains("-><init>(")) constructors++; }
                System.out.println("hook table: " + lines.length + " entries, " + constructors + " constructors, " + owners.size() + " classes");
                java.nio.file.Files.createDirectories(out.toPath());
                java.nio.file.Files.write(new File(out, "hooks.txt").toPath(), Arrays.asList(lines));
            }
            if (wanted.contains(type.getType())) classes.add(type.getType());
        }
        System.out.println("classes: " + total);
        if (classes.isEmpty()) return;
        BaksmaliOptions options = new BaksmaliOptions();
        options.apiLevel = 30; options.debugInfo = false;
        Baksmali.disassembleDexFile(dex, out, 1, options, classes);
        System.out.println("disassembled " + classes.size() + " classes to " + out);
    }
}
