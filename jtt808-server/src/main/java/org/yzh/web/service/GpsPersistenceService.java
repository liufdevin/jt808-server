package org.yzh.web.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.yzh.commons.util.JsonUtils;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0704;
import org.yzh.web.config.GpsPersistenceProperties;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@DependsOn("gpsSchemaInitializer")
@RequiredArgsConstructor
public class GpsPersistenceService {
    private final JdbcTemplate jdbc;
    private final GpsPersistenceProperties properties;
    private final GpsQueryService queries;
    private final ApplicationEventPublisher events;
    private static final int BATCH_SIZE = 500;
    private static final String DEVICE_SQL = """
            INSERT INTO gps_device (client_id, device_id, plate_no, protocol_version, last_seen_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              device_id=COALESCE(VALUES(device_id), device_id),
              plate_no=COALESCE(VALUES(plate_no), plate_no),
              protocol_version=VALUES(protocol_version),
              last_seen_at=GREATEST(last_seen_at, VALUES(last_seen_at))
            """;
    private static final String COLUMNS = "client_id,device_time,received_time,latitude_raw,longitude_raw,"
            + "latitude,longitude,altitude,speed,direction,warn_bit,status_bit,positioned,coordinate_system,"
            + "attributes_json,protocol_version,message_id,serial_no";
    private static final String PARAMETERS = "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?";
    private static final String TRACK_SQL = "INSERT INTO gps_device_track (" + COLUMNS
            + ",report_hash) VALUES (" + PARAMETERS + ",?) ON DUPLICATE KEY UPDATE id=id";
    private static final String LATEST_SQL = latestSql();

    @Transactional
    public void saveDevice(DeviceDO device) {
        validateClientId(device.getMobileNo());
        jdbc.update(DEVICE_SQL, device.getMobileNo(), nonBlank(device.getDeviceId()),
                nonBlank(device.getPlateNo()), device.getProtocolVersion(), Timestamp.valueOf(LocalDateTime.now()));
    }

    public void touchDevice(JTMessage message) {
        validateClientId(message.getClientId());
        jdbc.update(DEVICE_SQL, message.getClientId(), null, null, message.getProtocolVersion(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    @Transactional
    public void saveLocations(List<T0200> messages) {
        Assert.notNull(messages, "定位报文不能为空");
        List<Position> positions = new ArrayList<>(messages.size());
        LocalDateTime receivedTime = LocalDateTime.now();
        for (T0200 message : messages) {
            positions.add(snapshot(message, message, receivedTime));
        }
        persist(positions);
    }

    @Transactional
    public void saveBatchUpload(T0704 message) {
        Assert.isTrue(message.isVerified(), "批量定位报文校验失败");
        Assert.notNull(message.getItems(), "批量定位数据不能为空");
        Assert.isTrue(message.getTotal() == message.getItems().size(), "批量定位条数不匹配");
        List<Position> positions = new ArrayList<>(message.getItems().size());
        LocalDateTime receivedTime = LocalDateTime.now();
        for (T0200 item : message.getItems()) {
            positions.add(snapshot(item, message, receivedTime));
        }
        persist(positions);
    }

    private void persist(List<Position> positions) {
        if (positions.isEmpty()) return;
        // 固定设备锁定顺序，降低多设备批次交叉写入时的死锁风险。
        Map<String, Position> devices = new TreeMap<>();
        positions.forEach(position -> devices.put(position.clientId(), position));
        jdbc.batchUpdate(DEVICE_SQL, devices.values(), BATCH_SIZE, (ps, position) -> {
            ps.setString(1, position.clientId());
            ps.setString(2, position.deviceId());
            ps.setString(3, position.plateNo());
            ps.setInt(4, position.protocolVersion());
            ps.setTimestamp(5, Timestamp.valueOf(position.receivedTime()));
        });
        jdbc.batchUpdate(TRACK_SQL, positions, BATCH_SIZE, (ps, position) -> {
            bindPosition(ps, position);
            ps.setBytes(19, position.hash());
        });
        jdbc.batchUpdate(LATEST_SQL, positions, BATCH_SIZE, (ps, position) -> {
            bindPosition(ps, position);
            ps.setBytes(19, position.hash());
        });
        events.publishEvent(new GpsLocationsCommitted(queries.latestLocations(devices.keySet())));
    }

    private Position snapshot(T0200 message, JTMessage context, LocalDateTime receivedTime) {
        Assert.notNull(message, "定位数据不能为空");
        Assert.isTrue(context.isVerified() && message.isVerified(), "定位报文校验失败");
        validateClientId(context.getClientId());
        Assert.notNull(message.getDeviceTime(), "定位时间不能为空");
        Assert.isTrue(message.getLatitude() >= 0 && message.getLatitude() <= 90000000,
                "纬度超出协议范围");
        Assert.isTrue(message.getLongitude() >= 0 && message.getLongitude() <= 180000000,
                "经度超出协议范围");
        DeviceDO device = context.getSession() == null ? null
                : context.getSession().getAttribute(SessionKey.Device);
        String attributes = JsonUtils.toJson(message.getAttributes() == null
                ? Map.of() : new TreeMap<>(message.getAttributes()));
        int latitude = message.getLatitude();
        int longitude = message.getLongitude();
        BigDecimal lat = BigDecimal.valueOf((message.getStatusBit() & 4) == 0 ? latitude : -latitude, 6);
        BigDecimal lng = BigDecimal.valueOf((message.getStatusBit() & 8) == 0 ? longitude : -longitude, 6);
        String coordinateSystem = properties.getCoordinateSystem().name();
        String content = context.getClientId() + "|" + message.getDeviceTime() + "|" + latitude + "|"
                + longitude + "|" + message.getAltitude() + "|" + message.getSpeed() + "|"
                + message.getDirection() + "|" + message.getWarnBit() + "|" + message.getStatusBit()
                + "|" + coordinateSystem + "|" + attributes;
        return new Position(context.getClientId(), message.getDeviceTime(), receivedTime,
                latitude, longitude, lat, lng, message.getAltitude(), BigDecimal.valueOf(message.getSpeed(), 1),
                message.getDirection(), Integer.toUnsignedLong(message.getWarnBit()),
                Integer.toUnsignedLong(message.getStatusBit()), (message.getStatusBit() & 2) != 0,
                coordinateSystem, attributes, context.getProtocolVersion(), context.getMessageId(),
                context.getSerialNo(), device == null ? null : nonBlank(device.getDeviceId()),
                device == null ? null : nonBlank(device.getPlateNo()), sha256(content));
    }

    private static void bindPosition(PreparedStatement ps, Position p) throws SQLException {
        ps.setString(1, p.clientId());
        ps.setTimestamp(2, Timestamp.valueOf(p.deviceTime()));
        ps.setTimestamp(3, Timestamp.valueOf(p.receivedTime()));
        ps.setInt(4, p.latitudeRaw());
        ps.setInt(5, p.longitudeRaw());
        ps.setBigDecimal(6, p.latitude());
        ps.setBigDecimal(7, p.longitude());
        ps.setInt(8, p.altitude());
        ps.setBigDecimal(9, p.speed());
        ps.setInt(10, p.direction());
        ps.setLong(11, p.warnBit());
        ps.setLong(12, p.statusBit());
        ps.setBoolean(13, p.positioned());
        ps.setString(14, p.coordinateSystem());
        ps.setString(15, p.attributes());
        ps.setInt(16, p.protocolVersion());
        ps.setInt(17, p.messageId());
        ps.setInt(18, p.serialNo());
    }

    private static String latestSql() {
        String condition = "VALUES(device_time)>device_time OR "
                + "(VALUES(device_time)=device_time AND VALUES(received_time)>=received_time)";
        List<String> updates = new ArrayList<>();
        updates.add("revision=revision+CASE WHEN (" + condition
                + ") AND NOT(report_hash <=> VALUES(report_hash)) THEN 1 ELSE 0 END");
        // MySQL 顺序执行赋值，比较所用的时间字段必须放到最后更新。
        for (String column : COLUMNS.split(",")) {
            if (!column.equals("client_id") && !column.equals("device_time") && !column.equals("received_time")) {
                updates.add(column + "=CASE WHEN " + condition + " THEN VALUES(" + column + ") ELSE " + column + " END");
            }
        }
        updates.add("report_hash=CASE WHEN " + condition + " THEN VALUES(report_hash) ELSE report_hash END");
        updates.add("received_time=CASE WHEN " + condition + " THEN VALUES(received_time) ELSE received_time END");
        updates.add("device_time=GREATEST(device_time,VALUES(device_time))");
        return "INSERT INTO gps_device_location (" + COLUMNS + ",report_hash,revision) VALUES (" + PARAMETERS
                + ",?,1) ON DUPLICATE KEY UPDATE " + String.join(",", updates);
    }

    private static void validateClientId(String clientId) {
        Assert.isTrue(clientId != null && clientId.matches("[0-9]{1,20}"), "终端编号必须为1到20位数字");
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static byte[] sha256(String content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Position(String clientId, LocalDateTime deviceTime, LocalDateTime receivedTime,
                            int latitudeRaw, int longitudeRaw, BigDecimal latitude, BigDecimal longitude,
                            int altitude, BigDecimal speed, int direction, long warnBit, long statusBit,
                            boolean positioned, String coordinateSystem, String attributes, int protocolVersion,
                            int messageId, int serialNo, String deviceId, String plateNo, byte[] hash) { }
}
