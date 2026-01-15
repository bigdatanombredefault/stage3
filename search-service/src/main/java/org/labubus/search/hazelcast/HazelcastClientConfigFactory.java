package org.labubus.search.hazelcast;

import org.labubus.search.config.SearchConfig;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.JavaSerializationFilterConfig;

/**
 * Builds Hazelcast {@link ClientConfig} for the Search Service.
 *
 * <p>The Search Service connects as a Hazelcast <em>client</em> (it does not start a member),
 * so it does not bind the Hazelcast member port on the host. Discovery uses an explicit
 * address list derived from {@code CLUSTER_NODES_LIST} and {@code hazelcast.port}.</p>
 */
public final class HazelcastClientConfigFactory {
    private HazelcastClientConfigFactory() {}

    /**
     * Creates a Hazelcast client configuration based on the provided settings.
     *
     * @param settings hazelcast settings (cluster name, member list, etc.)
     * @return the Hazelcast {@link ClientConfig}
     */
    public static ClientConfig build(SearchConfig.Hazelcast settings) {
        ClientConfig config = new ClientConfig();
        config.setClusterName(settings.clusterName());

        var network = config.getNetworkConfig();
        network.getAddresses().clear();
        settings.members().forEach(ip -> network.addAddress(ip + ":" + settings.port()));

        configureSerialization(config);
        return config;
    }

    private static void configureSerialization(ClientConfig config) {
        JavaSerializationFilterConfig filter = new JavaSerializationFilterConfig();
        filter.getWhitelist().addClasses("org.labubus.model.BookMetadata");
        config.getSerializationConfig().setJavaSerializationFilterConfig(filter);
    }
}
