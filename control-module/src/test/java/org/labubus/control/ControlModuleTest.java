package org.labubus.control;

import org.junit.jupiter.api.Test;
import org.labubus.control.client.ServiceClient;

import static org.junit.jupiter.api.Assertions.*;

public class ControlModuleTest {

	@Test
	public void testServiceClientCreation() {
		ServiceClient client = new ServiceClient(5000, 10000);
		assertNotNull(client);
		System.out.println("✅ Service client creation test passed!");
	}

	@Test
	public void testJsonParsing() {
		ServiceClient client = new ServiceClient(5000, 10000);

		String json = "{\"status\":\"success\",\"count\":42}";
		var jsonObj = client.parseJson(json);

		assertEquals("success", jsonObj.get("status").getAsString());
		assertEquals(42, jsonObj.get("count").getAsInt());

		System.out.println("✅ JSON parsing test passed!");
	}

	@Test
	public void testJsonSerialization() {
		ServiceClient client = new ServiceClient(5000, 10000);

		var obj = new TestObject("test-name", 123);
		String json = client.toJson(obj);

		assertTrue(json.contains("test-name"));
		assertTrue(json.contains("123"));

		System.out.println("✅ JSON serialization test passed!");
	}

	static class TestObject {
		String name;
		int value;

		TestObject(String name, int value) {
			this.name = name;
			this.value = value;
		}
	}
}