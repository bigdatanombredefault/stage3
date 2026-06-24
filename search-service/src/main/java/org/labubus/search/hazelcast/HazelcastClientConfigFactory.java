package org.labubus.search.hazelcast;

import org.labubus.search.config.SearchConfig;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.JavaSerializationFilterConfig;

public final class HazelcastClientConfigFactory {
    private HazelcastClientConfigFactory() {}

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
