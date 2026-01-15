package org.labubus.indexing;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.labubus.indexing.service.IndexingService;
import org.labubus.indexing.storage.DatalakeReader;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

class IndexingServiceTest {

	@Test
	void datalakeReader_scanBookIdsFromFiles_findsSortedIds(@TempDir Path datalakeDir) throws Exception {
		Files.writeString(datalakeDir.resolve("10_body.txt"), "hello");
		Files.writeString(datalakeDir.resolve("10_header.txt"), "Title: Ten");
		Files.writeString(datalakeDir.resolve("20_body.txt"), "world");
		Files.writeString(datalakeDir.resolve("abc_body.txt"), "ignored");

		DatalakeReader reader = new DatalakeReader(datalakeDir.toString(), "downloaded.txt");
		assertEquals(List.of(10, 20), reader.scanBookIdsFromFiles());
	}

	@Test
	void indexingService_rebuildIndexFromLocalFiles_populatesHazelcast(@TempDir Path datalakeDir) throws Exception {
		Files.writeString(datalakeDir.resolve("1_header.txt"), "Title: One\nAuthor: A\nLanguage: en\nRelease Date: 2001");
		Files.writeString(datalakeDir.resolve("1_body.txt"), "hello world");

		List<HazelcastInstance> instances = new ArrayList<>();
		try {

		String clusterName = "test-" + UUID.randomUUID();
		int basePort = findFreePort();
		List<String> members = List.of(
			"127.0.0.1:" + basePort,
			"127.0.0.1:" + (basePort + 1),
			"127.0.0.1:" + (basePort + 2)
		);

		for (int i = 0; i < 3; i++) {
			Config cfg = new Config();
			cfg.setClusterName(clusterName);
			cfg.getNetworkConfig().setPort(basePort);
			cfg.getNetworkConfig().setPortAutoIncrement(true);
			cfg.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
			cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
			cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).setMembers(members);
			cfg.getCPSubsystemConfig().setCPMemberCount(3);
			instances.add(Hazelcast.newHazelcastInstance(cfg));
		}

		HazelcastInstance hazelcast = instances.get(0);

		IndexingService indexingService = new IndexingService(
			hazelcast,
			datalakeDir.toString(),
			"downloaded.txt",
			"metadata",
			"inverted"
		);

		assertTrue(indexingService.isInvertedIndexEmpty());
		int rebuilt = indexingService.rebuildIndexFromLocalFiles();
		assertEquals(1, rebuilt);
		assertFalse(indexingService.isInvertedIndexEmpty());
		assertTrue(hazelcast.getMap("metadata").containsKey(1));
		assertTrue(hazelcast.getMultiMap("inverted").containsEntry("hello", "1"));
		} finally {
			for (HazelcastInstance instance : instances) {
				instance.shutdown();
			}
		}
	}

	private static int findFreePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			socket.setReuseAddress(true);
			return socket.getLocalPort();
		}
	}
}