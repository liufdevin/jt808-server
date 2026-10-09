package org.yzh.web.model.vo;

import java.time.LocalDateTime;

public record GpsDevice(String clientId, String deviceId, String plateNo, int protocolVersion,
                        LocalDateTime lastSeenAt, LocalDateTime createdAt, boolean online) { }
