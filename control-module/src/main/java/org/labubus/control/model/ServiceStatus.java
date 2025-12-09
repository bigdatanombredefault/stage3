package org.labubus.control.model;

public record ServiceStatus(
		String service,
		String status,
		boolean reachable,
		String message
) {
	public static ServiceStatus reachable(String service) {
		return new ServiceStatus(service, "running", true, "Service is healthy");
	}

	public static ServiceStatus unreachable(String service, String message) {
		return new ServiceStatus(service, "unreachable", false, message);
	}
}