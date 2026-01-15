package org.labubus.indexing.hazelcast;

import org.labubus.indexing.config.IndexingConfig;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import com.hazelcast.config.MultiMapConfig;
import com.hazelcast.config.MultiMapConfig.ValueCollectionType;
import com.hazelcast.config.NearCacheConfig;

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
        configureCpSubsystem(config, settings);
        configureDataStructures(config, settings);
        configureSerialization(config);
        return config;
    }

    private static void configureCluster(Config config, IndexingConfig.Hazelcast s) {
        config.setClusterName(s.clusterName());
    }

    private static void configureNetwork(Config config, IndexingConfig.Hazelcast s) {
        config.getNetworkConfig().setPort(s.port()).setPortAutoIncrement(false);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface(s.currentNodeIp());
        config.getNetworkConfig().setPublicAddress(s.currentNodeIp() + ":" + s.port());
        configureJoin(config, s);
    }

    private static void configureCpSubsystem(Config config, IndexingConfig.Hazelcast s) {
        int cpMembers = Math.max(1, Math.min(3, s.members().size()));
        config.getCPSubsystemConfig().setCPMemberCount(cpMembers);
        config.getCPSubsystemConfig().setGroupSize(cpMembers);
    }

    private static void configureJoin(Config config, IndexingConfig.Hazelcast s) {
        var join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);

        var tcpIp = join.getTcpIpConfig();
        tcpIp.setEnabled(true);
        tcpIp.getMembers().clear();
        s.members().forEach(member -> tcpIp.addMember(normalizeMemberAddress(member, s.port())));
    }

    private static String normalizeMemberAddress(String member, int defaultPort) {
        if (member == null) {
            return "";
        }
        String trimmed = member.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        // Accept either "ip" (uses defaultPort) or "ip:port".
        // This keeps the old behavior but enables multiple Hazelcast members per host.
        if (trimmed.contains(":")) {
            return trimmed;
        }
        return trimmed + ":" + defaultPort;
    }

    private static void configureDataStructures(Config config, IndexingConfig.Hazelcast s) {
        MapConfig metadataCfg = new MapConfig(s.metadataMapName())
            .setBackupCount(s.backupCount())
            .setAsyncBackupCount(s.asyncBackupCount());
        metadataCfg.getEvictionConfig()
            .setEvictionPolicy(EvictionPolicy.LRU)
            .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
            .setSize(100_000);
        metadataCfg.setNearCacheConfig(
            new NearCacheConfig()
                .setInMemoryFormat(InMemoryFormat.OBJECT)
                .setInvalidateOnChange(true)
                .setCacheLocalEntries(true)
        );
        config.addMapConfig(metadataCfg);

        config.addMultiMapConfig(
            new MultiMapConfig(s.invertedIndexName())
                .setBackupCount(s.backupCount())
                .setAsyncBackupCount(s.asyncBackupCount())
                .setValueCollectionType(ValueCollectionType.SET)
        );
    }

    private static void configureSerialization(Config config) {
        JavaSerializationFilterConfig filter = new JavaSerializationFilterConfig();
        filter.getWhitelist().addClasses("org.labubus.model.BookMetadata");
        config.getSerializationConfig().setJavaSerializationFilterConfig(filter);
    }
}
