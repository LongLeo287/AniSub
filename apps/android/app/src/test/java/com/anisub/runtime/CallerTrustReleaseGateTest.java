package com.anisub.runtime;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * RELEASE GATE: who may talk to AniSub. The rule is "UID -> package com.anibox.tv -> signed with the same
 * certificate as AniSub (the AniBox release key)". Nothing weaker may ever ship: AniSub is only usable with AniBox.
 *
 * <p>This test is written against behaviour, not against a class layout. Everything goes through {@link #trusts},
 * the single adapter to the service's trust decision (today {@code CallerPolicy.trusted(uid, packages)}, the exact
 * call {@code AniSubService.receive} makes before touching a Bundle). If a refactor moves or renames the trust code,
 * change ONLY that adapter so it still reaches the same decision the service uses; do not delete or relax a case.
 * The release (non-debug) rule is the one under test; debug builds of AniSub additionally accept
 * {@code com.anibox.tv.debug} and are not a product.
 */
public class CallerTrustReleaseGateTest {
    static final String ANIBOX = "com.anibox.tv";

    /** The single seam: would the service accept a message sent by {@code uid} on a release build? */
    static boolean trusts(int uid, final Map<Integer, String[]> packagesByUid, final Set<String> sameSignerAsAniSub) {
        return CallerPolicy.trusted(uid, new CallerPolicy.Packages() {
            public String[] packagesForUid(int u) { return packagesByUid.get(u); }
            public boolean sameSignerAsSelf(String packageName) { return sameSignerAsAniSub.contains(packageName); }
        });
    }

    private static Map<Integer, String[]> uids(Object... pairs) {
        Map<Integer, String[]> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((Integer) pairs[i], (String[]) pairs[i + 1]);
        return m;
    }

    private static Set<String> signers(String... packages) { return new HashSet<>(Arrays.asList(packages)); }

    @Test public void aniBoxWithTheSameSignerIsAccepted() {
        assertTrue(trusts(10123, uids(10123, new String[]{ANIBOX}), signers(ANIBOX)));
    }

    @Test public void aniBoxSignedWithAnotherCertificateIsRejected() {
        assertFalse("same package name, foreign certificate", trusts(10123, uids(10123, new String[]{ANIBOX}), signers()));
        assertFalse(trusts(10123, uids(10123, new String[]{ANIBOX}), signers("com.some.other.app")));
    }

    @Test public void anyOtherPackageIsRejectedEvenWithTheSameCertificate() {
        for (String pkg : new String[]{"com.evil.app", "com.anibox.tv.debug", "com.anibox.tv.fake", "com.anibox.tvx", "com.anibox",
                "com.anibox.tv.", "COM.ANIBOX.TV", " com.anibox.tv", "com.anisub.runtime", "android", ""}) {
            assertFalse("package [" + pkg + "] with the AniBox signer", trusts(10200, uids(10200, new String[]{pkg}), signers(pkg, ANIBOX)));
        }
    }

    @Test public void aSharedUidOfUnrelatedPackagesIsRejected() {
        Map<Integer, String[]> shared = uids(10300, new String[]{"com.evil.one", "com.evil.two"});
        assertFalse(trusts(10300, shared, signers("com.evil.one", "com.evil.two", ANIBOX)));
        assertFalse(trusts(10301, uids(10301, new String[]{"com.anibox.tv.fake", "com.other"}), signers("com.anibox.tv.fake", "com.other")));
    }

    @Test public void theSignerCheckIsOnAniBoxItselfNotOnAnotherPackageOfTheUid() {
        // A UID whose AniBox entry has a foreign certificate is rejected even if a sibling package matches.
        Map<Integer, String[]> mixed = uids(10400, new String[]{"com.other.app", ANIBOX});
        assertFalse(trusts(10400, mixed, signers("com.other.app")));
    }

    @Test public void unknownMissingOrNegativeUidsAreRejected() {
        Map<Integer, String[]> known = uids(10123, new String[]{ANIBOX});
        assertFalse("unknown uid", trusts(99999, known, signers(ANIBOX)));
        assertFalse("uid without packages", trusts(10500, uids(10500, new String[0]), signers(ANIBOX)));
        assertFalse("negative uid", trusts(-1, known, signers(ANIBOX)));
        assertFalse("no package table at all", CallerPolicy.trusted(10123, null));
    }

    @Test public void aFailingLookupFailsClosed() {
        assertFalse("package lookup throws", CallerPolicy.trusted(10123, new CallerPolicy.Packages() {
            public String[] packagesForUid(int u) { throw new SecurityException("hidden"); }
            public boolean sameSignerAsSelf(String p) { return true; }
        }));
        assertFalse("signature check throws", CallerPolicy.trusted(10123, new CallerPolicy.Packages() {
            public String[] packagesForUid(int u) { return new String[]{ANIBOX}; }
            public boolean sameSignerAsSelf(String p) { throw new SecurityException("hidden"); }
        }));
    }

    @Test public void theReleaseRuleAcceptsExactlyOnePackageName() {
        assertEquals(java.util.Collections.singletonList(ANIBOX), CallerPolicy.clientPackages(false));
        // The debug AniBox is for debug builds of AniSub only; a release AniSub never lists it.
        assertFalse(CallerPolicy.clientPackages(false).contains("com.anibox.tv.debug"));
    }
}
