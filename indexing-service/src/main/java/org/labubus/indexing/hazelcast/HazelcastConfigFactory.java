package org.labubus.indexing.hazelcast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

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

        // In Docker, the container typically does NOT have the host's physical IP.
        // If we force interface matching to CURRENT_NODE_IP in that situation, Hazelcast fails to start.
        // So we only enable interface matching when CURRENT_NODE_IP is actually assigned locally.
        if (isLocalInterfaceAddress(s.currentNodeIp())) {
            config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface(s.currentNodeIp());
        } else {
            config.getNetworkConfig().getInterfaces().setEnabled(false);
        }

        // Still advertise the node's externally reachable address (host IP + published port).
        config.getNetworkConfig().setPublicAddress(s.currentNodeIp() + ":" + s.port());
        configureJoin(config, s);
    }

    private static boolean isLocalInterfaceAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String trimmed = ip.trim();
        if ("localhost".equalsIgnoreCase(trimmed) || "127.0.0.1".equals(trimmed)) {
            return true;
        }

        try {
            InetAddress target = InetAddress.getByName(trimmed);
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return false;
            }
            while (ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.equals(target)) {
                        return true;
                    }
                }
            }
        } catch (java.net.UnknownHostException | java.net.SocketException | SecurityException ignored) {
            // If anything goes wrong (DNS, permissions), fall back to disabling interface matching.
        }
        return false;
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
        java.util.LinkedHashSet<String> expanded = new java.util.LinkedHashSet<>();
        for (String member : s.members()) {
            expanded.addAll(expandMemberAddresses(member, s.memberPorts(), s.port()));
        }
        expanded.stream().filter(addr -> !addr.isBlank()).forEach(tcpIp::addMember);
    }

    private static java.util.List<String> expandMemberAddresses(String member, java.util.List<Integer> memberPorts, int defaultPort) {
        if (member == null) {
            return java.util.List.of();
        }
        String trimmed = member.trim();
        if (trimmed.isEmpty()) {
            return java.util.List.of();
        }

        // If the user already specified ip:port, trust it.
        if (trimmed.contains(":")) {
            return java.util.List.of(trimmed);
        }

        // If only IPs are provided, expand to all known member ports.
        if (memberPorts != null && !memberPorts.isEmpty()) {
            return memberPorts.stream().map(p -> trimmed + ":" + p).toList();
        }

        // Fallback to the service's own default port.
        return java.util.List.of(trimmed + ":" + defaultPort);
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
