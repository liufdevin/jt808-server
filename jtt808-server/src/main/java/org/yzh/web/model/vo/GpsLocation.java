package org.yzh.web.model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/** 经纬度为度，速度为 km/h；revision 是每台设备最新位置的递增版本。 */
public record GpsLocation(String clientId, Long trackId, Long revision,
                          LocalDateTime deviceTime, LocalDateTime receivedTime,
                          BigDecimal latitude, BigDecimal longitude, int altitude,
                          BigDecimal speed, int direction, long warnBit, long statusBit,
                          boolean positioned, String coordinateSystem, Map<String, Object> attributes,
                          int protocolVersion, int messageId, int serialNo) { }
