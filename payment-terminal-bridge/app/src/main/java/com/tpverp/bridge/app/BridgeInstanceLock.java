package com.tpverp.bridge.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class BridgeInstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private BridgeInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static BridgeInstanceLock acquire(Path dataDirectory) throws IOException {
        var directory = dataDirectory.normalize().toAbsolutePath();
        Files.createDirectories(directory);
        var channel = FileChannel.open(directory.resolve(".bridge.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("Another payment terminal bridge uses this data directory");
            return new BridgeInstanceLock(channel, lock);
        } catch (OverlappingFileLockException exception) {
            closeQuietly(channel);
            throw new IllegalStateException("Another payment terminal bridge uses this data directory", exception);
        } catch (RuntimeException | IOException exception) {
            closeQuietly(channel);
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
