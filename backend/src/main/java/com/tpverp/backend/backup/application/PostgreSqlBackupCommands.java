package com.tpverp.backend.backup.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PostgreSqlBackupCommands {

    private final String databaseUrl;
    private final String databaseUser;
    private final String databasePassword;
    private final String pgDumpCommand;
    private final String pgRestoreCommand;

    public PostgreSqlBackupCommands(
            String databaseUrl,
            String databaseUser,
            String databasePassword,
            String pgDumpCommand,
            String pgRestoreCommand) {
        this.databaseUrl = databaseUrl.replaceFirst("^jdbc:", "");
        this.databaseUser = databaseUser;
        this.databasePassword = databasePassword;
        this.pgDumpCommand = pgDumpCommand;
        this.pgRestoreCommand = pgRestoreCommand;
    }

    public void dump(Path destination) {
        run(List.of(
                pgDumpCommand,
                "--format=custom",
                "--no-password",
                "--username=" + databaseUser,
                "--file=" + destination.toAbsolutePath(),
                "--dbname=" + databaseUrl));
    }

    public void restore(Path source) {
        run(List.of(
                pgRestoreCommand,
                "--clean",
                "--if-exists",
                "--single-transaction",
                "--no-password",
                "--username=" + databaseUser,
                "--dbname=" + databaseUrl,
                source.toAbsolutePath().toString()));
    }

    private void run(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
            builder.redirectErrorStream(true);
            builder.environment().put("PGPASSWORD", databasePassword);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "La herramienta PostgreSQL termino con codigo " + exitCode + ": " + output.trim());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("La operacion PostgreSQL fue interrumpida", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo ejecutar la herramienta PostgreSQL", exception);
        }
    }
}
