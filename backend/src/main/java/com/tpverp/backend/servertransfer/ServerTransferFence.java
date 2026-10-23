package com.tpverp.backend.servertransfer;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;

@Component
public class ServerTransferFence {
    private final ReentrantReadWriteLock lock=new ReentrantReadWriteLock(true);
    private final ServerTransferJournal journal;
    public ServerTransferFence(ServerTransferJournal journal) { this.journal=journal; }
    public boolean frozen() { return journal.frozen() || journal.recoveryFrozen(); }
    public boolean enter() {
        lock.readLock().lock();
        if (frozen()) { lock.readLock().unlock(); return false; }
        return true;
    }
    public void leave() { lock.readLock().unlock(); }
    public void exclusive(Runnable action) {
        lock.writeLock().lock();
        try { action.run(); } finally { lock.writeLock().unlock(); }
    }
}
