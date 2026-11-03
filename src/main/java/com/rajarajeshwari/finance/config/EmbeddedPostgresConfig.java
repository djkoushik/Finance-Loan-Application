package com.rajarajeshwari.finance.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;

/**
 * Starts a PostgreSQL server bundled inside the application so the app runs fully offline.
 * Data lives in {@code finance.data-dir} and survives restarts.
 * If a server from an earlier run is still listening on the port (e.g. the app was killed), it is reused.
 */
@Configuration
public class EmbeddedPostgresConfig {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    /** Owns the (optional) embedded server and the connection pool. */
    public static class Database implements AutoCloseable {
        private final EmbeddedPostgres server; // null when attached to an already running server
        private final HikariDataSource pool;

        Database(EmbeddedPostgres server, HikariDataSource pool) {
            this.server = server;
            this.pool = pool;
        }

        public DataSource dataSource() {
            return pool;
        }

        @Override
        public void close() throws IOException {
            pool.close();
            if (server != null) {
                server.close();
            }
        }
    }

    @Bean(destroyMethod = "close")
    public Database database(@Value("${finance.data-dir}") String dataDir,
                             @Value("${finance.db-port}") int port,
                             @Value("${finance.pg-template-dir:}") String templateDir) throws IOException {
        EmbeddedPostgres server = null;
        if (!portOpen(port)) {
            Path dir = Paths.get(dataDir.replace("${user.home}", System.getProperty("user.home")))
                    .toAbsolutePath().resolve("pgdata");
            seedFromTemplate(templateDir, dir);
            // port closed => no server is running on this data directory; a lock file left by a crash/forced shutdown
            // (or a reused PID) can make PostgreSQL refuse to start and burn the whole startup wait
            deleteQuietly(dir.resolve("postmaster.pid"));
            deleteQuietly(dir.resolve("epg-lock"));
            long t0 = System.currentTimeMillis();
            server = EmbeddedPostgres.builder()
                    .setDataDirectory(dir)
                    .setCleanDataDirectory(false)
                    .setPort(port)
                    // small footprint: the app uses at most 8 connections, so skip PostgreSQL's 300-connection default
                    .setServerConfig("max_connections", "20")
                    .setServerConfig("shared_buffers", "32MB")
                    .setServerConfig("log_min_messages", "warning")
                    // unpack the PostgreSQL binaries next to the data (not into %TEMP%, which Windows/cleaners wipe,
                    // forcing a slow re-extract of ~1400 files on the next start)
                    .setOverrideWorkingDirectory(dir.getParent().resolve("pgruntime").toFile())
                    // the default is 10s, too short for slow disks / antivirus scanning
                    .setPGStartupWait(Duration.ofSeconds(120))
                    .start();
            LOG.info("STARTUP TIMING: database started in {} ms", System.currentTimeMillis() - t0);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + port + "/postgres");
        cfg.setUsername("postgres");
        cfg.setMaximumPoolSize(8);
        // a server left over from an earlier run may still be shutting down: retry instead of failing at once
        cfg.setInitializationFailTimeout(60000);
        cfg.setPoolName("finance");
        return new Database(server, new HikariDataSource(cfg));
    }

    @Bean
    public DataSource dataSource(Database db) {
        return db.dataSource();
    }

    /**
     * A brand-new installation would otherwise unpack ~1400 PostgreSQL files and run initdb (1+ minute on a slow
     * disk with antivirus). The installer ships a ready-made copy (database already initialised and migrated);
     * copying it is several times faster. Never touches an existing database.
     */
    private static void seedFromTemplate(String templateDir, Path pgdata) {
        if (templateDir == null || templateDir.trim().isEmpty()) {
            return;
        }
        Path tpl = Paths.get(templateDir).toAbsolutePath();
        try {
            long t0 = System.currentTimeMillis();
            Path runtime = pgdata.getParent().resolve("pgruntime");
            // the unpacked PostgreSQL programs are also missing on an upgraded installation (older builds used %TEMP%)
            if (!Files.exists(runtime) && Files.isDirectory(tpl.resolve("pgruntime"))) {
                copyAtomically(tpl.resolve("pgruntime"), runtime);
            }
            Path tplData = tpl.resolve("pgdata");
            if (!Files.exists(pgdata.resolve("PG_VERSION")) && Files.exists(tplData.resolve("PG_VERSION"))) {
                deleteTree(pgdata); // leftover of an earlier failed first start
                copyAtomically(tplData, pgdata);
            }
            LOG.info("STARTUP TIMING: database template step took {} ms", System.currentTimeMillis() - t0);
        } catch (IOException e) {
            // fall back to the normal slow path (initdb)
            LOG.warn("Could not use database template", e);
        }
    }

    private static void copyAtomically(Path from, Path to) throws IOException {
        Path tmp = to.resolveSibling(to.getFileName() + ".tmp");
        deleteTree(tmp);
        Files.createDirectories(tmp.getParent());
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(tmp.resolve(from.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, tmp.resolve(from.relativize(f).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
        Files.move(tmp, to);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // PostgreSQL will report it if it really matters
        }
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
