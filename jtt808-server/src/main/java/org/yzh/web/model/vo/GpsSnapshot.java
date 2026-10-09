package org.yzh.web.model.vo;

import java.util.List;

public record GpsSnapshot(List<GpsLocation> locations, List<String> missingClientIds) { }
