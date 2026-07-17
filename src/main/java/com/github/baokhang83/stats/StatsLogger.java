package com.github.baokhang83.stats;

import com.github.baokhang83.analysis.PackageMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Persists a {@link PackageMetrics} snapshot as a pretty-printed JSON file under
 * {@code {projectRoot}/.stats/{packageName}/{yyyyMMdd_HHmmss_SSS}.json}.
 */
public final class StatsLogger {

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
                             .withZone(ZoneOffset.UTC);

    private final Path projectRoot;
    private final ObjectMapper mapper;

    public StatsLogger(Path projectRoot, ObjectMapper mapper) {
        this.projectRoot = projectRoot;
        this.mapper      = mapper;
    }

    /**
     * Writes {@code metrics} to disk and returns the path of the created file.
     *
     * @param metrics     the metrics to persist
     * @param capturedAt  the instant used to build the filename (should match
     *                    {@code metrics.timestamp} so filenames sort chronologically)
     */
    public Path writeSnapshot(PackageMetrics metrics, Instant capturedAt) throws IOException {
        Path snapshotDir = projectRoot.resolve(".stats")
                                      .resolve(metrics.packageName);
        Files.createDirectories(snapshotDir);

        String filename = FILE_TS.format(capturedAt) + ".json";
        Path snapshotFile = snapshotDir.resolve(filename);

        mapper.writerWithDefaultPrettyPrinter().writeValue(snapshotFile.toFile(), metrics);
        return snapshotFile;
    }
}
