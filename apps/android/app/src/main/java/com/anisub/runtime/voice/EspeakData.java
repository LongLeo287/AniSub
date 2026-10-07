package com.anisub.runtime.voice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One app-owned espeak-ng data directory for every voice pack.
 *
 * <p>sherpa-onnx initialises espeak-ng ONCE per process with the first data path it is given, and
 * espeak reads dictionaries from that path lazily when the voice changes. Pointing it at a pack's own
 * directory breaks as soon as that pack is deleted or updated while the process lives, or when a
 * second pack (another language) is loaded. So the verified espeak files of a pack are copied into
 * {@code <root>} (same names; every pack ships the same espeak-ng-data snapshot, the vi and en
 * packs' common files have identical SHA-256) and the engine always uses {@code <root>}; files of a
 * newly loaded pack are added. Each copy is checked against the catalog's pinned SHA-256. Run on the
 * loader thread, never the main thread.
 */
public final class EspeakData {
    private EspeakData() { }

    /**
     * @param root the shared directory (e.g. files/espeak-ng-data)
     * @param packDirectory verified, leased pack directory
     * @return {@code root}, holding every espeak file of {@code pack}
     */
    public static synchronized File prepare(File root, File packDirectory, VoiceCatalog.Pack pack) throws IOException {
        String prefix = pack.dataDir + "/";
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("STORAGE");
        for (VoiceCatalog.PackFile f : pack.files) {
            if (!f.path.startsWith(prefix)) continue;
            String rel = f.path.substring(prefix.length());
            if (rel.isEmpty() || rel.contains("..") || rel.startsWith("/")) throw new IOException("CORRUPT path");
            File target = new File(root, rel);
            if (target.isFile() && target.length() == f.bytes && VoicePackManager.sha256(target).equals(f.sha256)) continue;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("STORAGE");
            File temp = new File(parent, target.getName() + ".tmp");
            copy(new File(packDirectory, f.path), temp);
            if (temp.length() != f.bytes || !VoicePackManager.sha256(temp).equals(f.sha256)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                throw new IOException("CORRUPT espeak data");
            }
            if (target.exists() && !target.delete()) throw new IOException("STORAGE");
            if (!temp.renameTo(target)) throw new IOException("STORAGE");
        }
        return root;
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
