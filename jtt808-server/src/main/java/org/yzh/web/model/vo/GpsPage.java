package org.yzh.web.model.vo;

import java.util.List;

public record GpsPage<T>(List<T> items, boolean hasMore, String nextCursor) { }
