package com.anisub.runtime.models;

import java.io.*;
import java.security.*;
import java.util.*;
public class IntegrityVerifier {
    /** Rejects every redirected path, undeclared file, missing asset, byte mismatch and digest mismatch. */
    public VerificationResult verify(File root, ModelManifest manifest) {
        if (root == null || manifest == null) return VerificationResult.corrupt();
        try {
            if (!root.isDirectory() || !root.getAbsoluteFile().equals(root.getCanonicalFile())) return VerificationResult.corrupt();
            Set<String> files = new HashSet<>(), directories = new HashSet<>();
            for (ModelManifest.Asset asset : manifest.assets) {
                String path = asset.path;
                for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) directories.add(path.substring(0, slash));
            }
            collect(root, root, files, directories, manifest.assets.size(), 0);
            long total = 0;
            for (ModelManifest.Asset asset : manifest.assets) {
                if (!files.remove(asset.path)) return VerificationResult.corrupt();
                File file = new File(root, asset.path);
                if (!file.isFile() || file.length() != asset.bytes) return VerificationResult.corrupt();
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long bytes = 0;
                try (InputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[64 * 1024]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (bytes > asset.bytes - n) return VerificationResult.corrupt();
                        bytes += n; digest.update(buffer, 0, n);
                    }
                }
                if (bytes != asset.bytes || !hex(digest.digest()).equals(asset.sha256)) return VerificationResult.corrupt();
                if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) return VerificationResult.corrupt();
                if (total > Long.MAX_VALUE - bytes) return VerificationResult.corrupt(); total += bytes;
            }
            return files.isEmpty() && total == manifest.expandedBytes ? VerificationResult.valid(total) : VerificationResult.corrupt();
        } catch (IOException | GeneralSecurityException | IllegalArgumentException ex) { return VerificationResult.corrupt(); }
    }
    private static void collect(File root, File directory, Set<String> files, Set<String> directories, int max, int depth) throws IOException {
        if (depth > 32 || !directory.getAbsoluteFile().equals(directory.getCanonicalFile())) throw new IOException("redirected directory");
        File[] children = directory.listFiles(); if (children == null) throw new IOException("unreadable directory");
        if (children.length > 512) throw new IOException("directory entries");
        for (File child : children) {
            if (!child.getAbsoluteFile().equals(child.getCanonicalFile())) throw new IOException("redirected asset");
            String relative = child.getPath().substring(root.getPath().length() + 1).replace(File.separatorChar, '/');
            if (child.isDirectory()) {
                if (!directories.remove(relative)) throw new IOException("undeclared directory");
                collect(root, child, files, directories, max, depth + 1);
            }
            else if (child.isFile()) {
                ModelManifest.requireRelativePath(relative); if (!files.add(relative) || files.size() > max) throw new IOException("unexpected asset");
            } else throw new IOException("non-file asset");
        }
    }
    static String hex(byte[] bytes) { char[] alphabet = "0123456789abcdef".toCharArray(); StringBuilder out = new StringBuilder(bytes.length * 2); for (byte b : bytes) { out.append(alphabet[(b & 255) >>> 4]); out.append(alphabet[b & 15]); } return out.toString(); }
}
