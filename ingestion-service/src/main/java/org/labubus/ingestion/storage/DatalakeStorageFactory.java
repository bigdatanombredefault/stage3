package org.labubus.ingestion.storage;

import org.labubus.ingestion.config.IngestionConfig;

/**
 * Creates {@link DatalakeStorage} implementations based on configuration.
 */
public final class DatalakeStorageFactory {
    private DatalakeStorageFactory() {}

    /**
     * Creates the configured datalake storage implementation.
     *
     * @param cfg datalake settings
     * @return an implementation of {@link DatalakeStorage}
     */
    public static DatalakeStorage create(IngestionConfig.Datalake cfg) {
        return switch (cfg.type()) {
            case "timestamp" -> new TimestampDatalakeStorage(cfg.path(), cfg.trackingFilename());
            case "bucket" -> new BucketDatalakeStorage(cfg.path(), cfg.bucketSize(), cfg.trackingFilename());
            default -> throw new IllegalArgumentException("Unsupported datalake.type: " + cfg.type());
        };
    }
}
