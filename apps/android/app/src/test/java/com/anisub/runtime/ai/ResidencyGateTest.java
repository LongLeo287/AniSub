package com.anisub.runtime.ai;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResidencyGateTest {
    @Test public void sessionCannotStealPreviewOrSmokeOwner() throws Exception {
        ResidencyGate gate=new ResidencyGate(); assertTrue(gate.preview());
        assertFalse(gate.session());assertFalse(gate.smoke(0));
        assertEquals(ResidencyGate.Owner.PREVIEW,gate.owner());
        gate.end(ResidencyGate.Owner.PREVIEW);assertTrue(gate.smoke(0));
        assertFalse(gate.session());assertFalse(gate.preview());
    }
    @Test public void staleOwnerReleaseCannotEndSession() {
        ResidencyGate gate=new ResidencyGate();assertTrue(gate.session());
        gate.end(ResidencyGate.Owner.PREVIEW);gate.end(ResidencyGate.Owner.SMOKE);
        assertEquals(ResidencyGate.Owner.SESSION,gate.owner());
        assertTrue(gate.session());gate.end(ResidencyGate.Owner.SESSION);
        assertTrue(gate.preview());
    }
    @Test public void interruptedSmokeWaitNeverClaimsOwnership() throws Exception {
        ResidencyGate gate=new ResidencyGate();assertTrue(gate.session());
        Thread.currentThread().interrupt();
        try {gate.smoke(1000);fail();}catch(InterruptedException expected){}
        finally {Thread.interrupted();}
        assertEquals(ResidencyGate.Owner.SESSION,gate.owner());
    }
}
