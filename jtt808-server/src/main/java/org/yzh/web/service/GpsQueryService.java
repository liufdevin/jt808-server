package org.yzh.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.yzh.commons.util.JsonUtils;
import org.yzh.web.model.vo.*;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GpsQueryService {
    private final JdbcTemplate jdbc;
    private final SessionManager sessions;
    private static final String FIELDS = "client_id,device_time,received_time,latitude,longitude,altitude,"
            + "speed,direction,warn_bit,status_bit,positioned,coordinate_system,attributes_json,"
            + "protocol_version,message_id,serial_no";

    public GpsPage<GpsDevice> devices(String afterClientId, int limit) {
        limit(limit, 500);
        List<Object> args = new ArrayList<>();
        String sql = "SELECT * FROM gps_device";
        if (afterClientId != null && !afterClientId.isBlank()) {
            clientId(afterClientId);
            sql += " WHERE client_id>?";
            args.add(afterClientId);
        }
        args.add(limit + 1);
        List<GpsDevice> rows = jdbc.query(sql + " ORDER BY client_id LIMIT ?", (rs, n) -> {
            String clientId = rs.getString("client_id");
            Session session = sessions.get(clientId);
            return new GpsDevice(clientId, rs.getString("device_id"), rs.getString("plate_no"),
                    rs.getInt("protocol_version"), rs.getTimestamp("last_seen_at").toLocalDateTime(),
                    rs.getTimestamp("created_at").toLocalDateTime(), session != null && session.isRegistered());
        }, args.toArray());
        boolean more = rows.size() > limit;
        List<GpsDevice> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new GpsPage<>(items, more, more ? items.get(items.size() - 1).clientId() : null);
    }

    public Optional<GpsLocation> latest(String clientId) {
        clientId(clientId);
        return latestLocations(List.of(clientId)).stream().findFirst();
    }

    public GpsSnapshot snapshot(List<String> clientIds) {
        List<String> ids = clientIds(clientIds);
        List<GpsLocation> locations = latestLocations(ids);
        Set<String> present = new HashSet<>();
        locations.forEach(point -> present.add(point.clientId()));
        return new GpsSnapshot(locations, ids.stream().filter(id -> !present.contains(id)).toList());
    }

    // 供保存事务读取最终的最新位置；每批查询有界，与订阅 APP 数量无关。
    public List<GpsLocation> latestLocations(Collection<String> clientIds) {
        if (clientIds.isEmpty()) return List.of();
        List<String> ids = new ArrayList<>(new TreeSet<>(clientIds));
        List<GpsLocation> result = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 100) {
            List<String> batch = ids.subList(start, Math.min(start + 100, ids.size()));
            String marks = String.join(",", Collections.nCopies(batch.size(), "?"));
            result.addAll(jdbc.query("SELECT " + FIELDS + ",NULL AS track_id,revision FROM gps_device_location"
                    + " WHERE client_id IN (" + marks + ") ORDER BY client_id", GpsQueryService::location,
                    batch.toArray()));
        }
        return List.copyOf(result);
    }

    public GpsPage<GpsLocation> tracks(String clientId, LocalDateTime from, LocalDateTime to,
                                       String cursor, int limit) {
        clientId(clientId);
        limit(limit, 1000);
        Assert.isTrue(from != null && to != null && from.isBefore(to), "from 必须早于 to");
        Assert.isTrue(Duration.between(from, to).compareTo(Duration.ofDays(31)) <= 0,
                "每次轨迹查询的时间范围不能超过31天");
        List<Object> args = new ArrayList<>(List.of(clientId, Timestamp.valueOf(from), Timestamp.valueOf(to)));
        String sql = "SELECT " + FIELDS + ",id AS track_id,NULL AS revision FROM gps_device_track"
                + " WHERE client_id=? AND device_time>=? AND device_time<?";
        if (cursor != null && !cursor.isBlank()) {
            TrackCursor after = decodeCursor(cursor, clientId);
            Assert.isTrue(!after.time().isBefore(from) && after.time().isBefore(to), "cursor 不在查询时间范围内");
            sql += " AND (device_time>? OR (device_time=? AND id>?))";
            args.add(Timestamp.valueOf(after.time()));
            args.add(Timestamp.valueOf(after.time()));
            args.add(after.id());
        }
        args.add(limit + 1);
        List<GpsLocation> rows = jdbc.query(sql + " ORDER BY device_time,id LIMIT ?",
                GpsQueryService::location, args.toArray());
        boolean more = rows.size() > limit;
        List<GpsLocation> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next = more ? encodeCursor(items.get(items.size() - 1)) : null;
        return new GpsPage<>(items, more, next);
    }

    public static List<String> clientIds(List<String> ids) {
        Assert.isTrue(ids != null && !ids.isEmpty() && ids.size() <= 100, "一次必须指定1到100个设备编号");
        ids.forEach(GpsQueryService::clientId);
        return ids.stream().distinct().sorted().toList();
    }

    public static void clientId(String id) {
        Assert.isTrue(id != null && id.matches("[0-9]{1,20}"), "设备编号必须为1到20位数字");
    }

    private static void limit(int value, int max) {
        Assert.isTrue(value >= 1 && value <= max, "limit 必须在1到" + max + "之间");
    }

    private static GpsLocation location(ResultSet rs, int row) throws SQLException {
        Map<String, Object> attributes = JsonUtils.toObj(rs.getString("attributes_json"), new TypeReference<>() {});
        if (attributes == null) throw new SQLException("数据库定位附加属性格式错误");
        Long track = rs.getObject("track_id", Long.class);
        Long revision = rs.getObject("revision", Long.class);
        return new GpsLocation(rs.getString("client_id"), track, revision,
                rs.getTimestamp("device_time").toLocalDateTime(), rs.getTimestamp("received_time").toLocalDateTime(),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getInt("altitude"),
                rs.getBigDecimal("speed"), rs.getInt("direction"), rs.getLong("warn_bit"), rs.getLong("status_bit"),
                rs.getBoolean("positioned"), rs.getString("coordinate_system"), attributes,
                rs.getInt("protocol_version"), rs.getInt("message_id"), rs.getInt("serial_no"));
    }

    private static String encodeCursor(GpsLocation last) {
        String value = last.clientId() + "|" + last.deviceTime() + "|" + last.trackId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static TrackCursor decodeCursor(String cursor, String clientId) {
        try {
            Assert.isTrue(cursor.length() <= 160, "cursor 无效");
            String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            Assert.isTrue(fields.length == 3 && fields[0].equals(clientId), "cursor 设备不匹配");
            long id = Long.parseLong(fields[2]);
            Assert.isTrue(id > 0, "cursor 无效");
            return new TrackCursor(LocalDateTime.parse(fields[1]), id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cursor 无效或不属于该设备", e);
        }
    }

    private record TrackCursor(LocalDateTime time, long id) { }
}
