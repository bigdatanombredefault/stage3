package org.labubus.control.model;

import java.util.List;

public record PipelineRequest(
		List<Integer> bookIds,
		boolean rebuildIndex
) {}