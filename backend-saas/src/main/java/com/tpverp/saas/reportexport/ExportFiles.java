package com.tpverp.saas.reportexport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Both report families share the download slots and temporary file lifecycle. */
@Component
final class ExportFiles {
    private static final Logger LOG = LoggerFactory.getLogger(ExportFiles.class);
    private final Semaphore exports = new Semaphore(2);

    @FunctionalInterface
    interface FileWriter {
        void write(Path path) throws IOException;
    }

    PreparedReport prepare(String format, FileWriter writer) throws IOException {
        if (!exports.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Hay dos exportaciones en curso");
        }
        PreparedReport prepared = null;
        boolean ready = false;
        try {
            prepared = new PreparedReport(Files.createTempFile(ReportExportService.TEMP_PREFIX, "." + format), exports);
            writer.write(prepared.path);
            PreparedReport result = prepared;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) result.close();
                }
            });
            ready = true;
            return result;
        } finally {
            if (!ready) {
                if (prepared == null) exports.release();
                else prepared.close();
            }
        }
    }

    static final class PreparedReport implements AutoCloseable {
        private final Path path;
        private final Semaphore exports;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PreparedReport(Path path, Semaphore exports) {
            this.path = path;
            this.exports = exports;
        }

        long size() throws IOException {
            return Files.size(path);
        }

        void writeTo(OutputStream output) throws IOException {
            Files.copy(path, output);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOG.warn("No se pudo eliminar el archivo temporal de exportación", exception);
                } finally {
                    exports.release();
                }
            }
        }
    }
}
