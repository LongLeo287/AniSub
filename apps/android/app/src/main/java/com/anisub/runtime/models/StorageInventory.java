package com.anisub.runtime.models;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Bounded app-private disk accounting/cache cleanup; never follows aliases or symbolic links. */
public final class StorageInventory {
    private static final int MAX_FILES = 8192, MAX_DEPTH = 24;
    private StorageInventory() { }
    public static long bytes(File root) throws IOException {
        File base = root.getCanonicalFile();
        return scan(base, base, new HashSet<>(), new int[]{0}, 0, false);
    }
    /** Only the supplied cache directory's children are removed, not the directory itself. */
    public static void clearCache(File root) throws IOException {
        File base = root.getCanonicalFile();
        if (!base.isDirectory()) return;
        // Validate the entire bounded tree first, so a link/excessive tree never causes partial cleanup.
        scan(base, base, new HashSet<>(), new int[]{0}, 0, false);
        scan(base, base, new HashSet<>(), new int[]{0}, 0, true);
    }
    private static long scan(File base, File entry, Set<String> seen, int[] count, int depth, boolean remove) throws IOException {
        if (depth > MAX_DEPTH || ++count[0] > MAX_FILES) throw new IOException("storage bounds");
        File actual = entry.getCanonicalFile();
        if (!actual.equals(entry.getAbsoluteFile()) || (!actual.equals(base)
                && !actual.getPath().startsWith(base.getPath() + File.separator))) throw new IOException("storage path");
        if (!seen.add(actual.getPath())) throw new IOException("storage alias");
        if (!entry.exists()) return 0;
        long bytes = 0;
        if (entry.isDirectory()) {
            File[] children = entry.listFiles();
            if (children == null) throw new IOException("storage unreadable");
            for (File child : children) bytes += scan(base, child, seen, count, depth + 1, remove);
        } else bytes = entry.length();
        if (remove && !actual.equals(base) && !entry.delete()) throw new IOException("cache delete");
        return bytes;
    }
}
