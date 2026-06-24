package org.labubus.ingestion.storage;

import org.labubus.ingestion.config.IngestionConfig;

public final class DatalakeStorageFactory {
    private DatalakeStorageFactory() {}

    public static DatalakeStorage create(IngestionConfig.Datalake cfg) {
        return switch (cfg.type()) {
            case "timestamp" -> new TimestampDatalakeStorage(cfg.path(), cfg.trackingFilename());
            case "bucket" -> new BucketDatalakeStorage(cfg.path(), cfg.bucketSize(), cfg.trackingFilename());
            default -> throw new IllegalArgumentException("Unsupported datalake.type: " + cfg.type());
        };
    }
}
