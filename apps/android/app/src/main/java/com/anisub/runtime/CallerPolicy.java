package com.anisub.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Runtime caller authorization. AniBox is normally installed BEFORE AniSub, and Android only
 * grants a custom permission at the requester's install time, so the service cannot require
 * {@code com.anisub.runtime.BIND}. Trust is decided per message from the kernel-provided
 * sending UID: the UID must own an allowed client package name and that package must be signed
 * by the same certificate as AniSub. Checked before any Bundle decode.
 *
 * Release builds accept exactly {@link #CLIENT_PACKAGE}. Debug builds of AniSub additionally
 * accept {@link #DEBUG_CLIENT_PACKAGE} (AniBox debug), still with the same-signer check, so
 * debug-signed pairs can be tested end to end.
 */
public final class CallerPolicy {
    public static final String CLIENT_PACKAGE = "com.anibox.tv";
    public static final String DEBUG_CLIENT_PACKAGE = "com.anibox.tv.debug";
    /** Port over PackageManager so the rule is JVM-testable. */
    public interface Packages {
        /** Packages sharing the UID, or null when the UID is unknown. */
        String[] packagesForUid(int uid);
        /** True only when {@code packageName} is signed with AniSub's own signing certificate. */
        boolean sameSignerAsSelf(String packageName);
    }
    private CallerPolicy() { }

    public static List<String> clientPackages(boolean debugBuild) {
        return debugBuild ? Arrays.asList(CLIENT_PACKAGE, DEBUG_CLIENT_PACKAGE) : Collections.singletonList(CLIENT_PACKAGE);
    }

    /** Release rule: exact {@code com.anibox.tv}. */
    public static boolean trusted(int sendingUid, Packages packages) { return trusted(sendingUid, packages, false); }

    /** @param debugBuild pass {@code BuildConfig.DEBUG}; never true in release builds */
    public static boolean trusted(int sendingUid, Packages packages, boolean debugBuild) {
        if (sendingUid < 0 || packages == null) return false;
        String[] owned;
        try { owned = packages.packagesForUid(sendingUid); } catch (RuntimeException e) { return false; }
        if (owned == null) return false;
        List<String> uidPackages = Arrays.asList(owned);
        for (String client : clientPackages(debugBuild)) {
            if (!uidPackages.contains(client)) continue;
            try { if (packages.sameSignerAsSelf(client)) return true; } catch (RuntimeException e) { return false; }
        }
        return false;
    }
}
