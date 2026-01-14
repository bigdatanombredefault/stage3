package org.labubus.indexing.hazelcast;

import org.labubus.indexing.config.IndexingConfig;

import com.hazelcast.config.Config;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MultiMapConfig;

/**
 * Builds Hazelcast {@link Config} for the Indexing Service.
 *
 * <p>Uses TCP-IP discovery with a fixed member list and disables multicast/auto-detection.</p>
 */
public final class HazelcastConfigFactory {
    private HazelcastConfigFactory() {}

    /**
     * Creates a Hazelcast configuration based on the provided settings.
     *
     * @param settings hazelcast settings (cluster name, member list, map names, etc.)
     * @return the Hazelcast {@link Config}
     */
    public static Config build(IndexingConfig.Hazelcast settings) {
        Config config = new Config();
        configureCluster(config, settings);
        configureNetwork(config, settings);
        configureDataStructures(config, settings);
        configureSerialization(config);
        return config;
    }

    private static void configureCluster(Config config, IndexingConfig.Hazelcast s) {
        config.setClusterName(s.clusterName());
    }

    private static void configureNetwork(Config config, IndexingConfig.Hazelcast s) {
        config.getNetworkConfig().setPort(s.port()).setPortAutoIncrement(false);
        config.getNetworkConfig().setPublicAddress(s.currentNodeIp() + ":" + s.port());
        configureJoin(config, s);
    }

    private static void configureJoin(Config config, IndexingConfig.Hazelcast s) {
        var join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);

        var tcpIp = join.getTcpIpConfig();
        tcpIp.setEnabled(true);
        tcpIp.getMembers().clear();
        s.members().forEach(ip -> tcpIp.addMember(ip + ":" + s.port()));
    }

    private static void configureDataStructures(Config config, IndexingConfig.Hazelcast s) {
        config.addMapConfig(new MapConfig(s.metadataMapName()).setBackupCount(s.backupCount()));
        config.addMultiMapConfig(new MultiMapConfig(s.invertedIndexName()).setBackupCount(s.backupCount()));
    }

    private static void configureSerialization(Config config) {
        JavaSerializationFilterConfig filter = new JavaSerializationFilterConfig();
        filter.getWhitelist().addClasses("org.labubus.model.BookMetadata");
        config.getSerializationConfig().setJavaSerializationFilterConfig(filter);
    }
}
