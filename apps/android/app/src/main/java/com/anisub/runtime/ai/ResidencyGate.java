package com.anisub.runtime.ai;

/** One process-wide operation owner; native creation/release also stays on one engine loader. */
public final class ResidencyGate {
    public enum Owner { IDLE, SESSION, PREVIEW, SMOKE }
    private Owner owner=Owner.IDLE;
    public synchronized Owner owner(){return owner;}
    public synchronized boolean session(){if(owner!=Owner.IDLE&&owner!=Owner.SESSION)return false;owner=Owner.SESSION;return true;}
    public synchronized boolean preview(){if(owner!=Owner.IDLE)return false;owner=Owner.PREVIEW;return true;}
    public synchronized void end(Owner expected){if(owner==expected){owner=Owner.IDLE;notifyAll();}}
    public synchronized boolean smoke(long timeoutMs)throws InterruptedException{
        long end=System.currentTimeMillis()+timeoutMs;
        while(owner!=Owner.IDLE){long left=end-System.currentTimeMillis();if(left<=0)return false;wait(Math.min(left,1000));}
        owner=Owner.SMOKE;return true;
    }
}
