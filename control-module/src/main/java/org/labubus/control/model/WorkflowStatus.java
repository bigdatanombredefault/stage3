package org.labubus.control.model;

public record WorkflowStatus(
		String stage,
		String status,
		String message,
		int progress,
		int total
) {}