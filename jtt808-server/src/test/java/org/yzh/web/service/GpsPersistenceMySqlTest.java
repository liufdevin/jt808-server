package org.yzh.web.service;

import com.zaxxer.hikari.HikariDataSource;
import io.github.yezhihao.netmc.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0704;
import org.yzh.web.config.GpsPersistenceConfig;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式设置 GPS_MYSQL_TEST=true 才连接配置中的数据库；仅清理本测试生成的设备记录。 */
@EnabledIfEnvironmentVariable(named = "GPS_MYSQL_TEST", matches = "true")
@SpringJUnitConfig(GpsPersistenceMySqlTest.TestConfig.class)
class GpsPersistenceMySqlTest {
    @Autowired private GpsPersistenceService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GpsQueryService queries;
    @Autowired private GpsSubscriptionService subscriptions;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    private final Set<String> clients = new HashSet<>();
    private final LocalDateTime time = LocalDateTime.of(2026, 10, 9, 10, 0);

    @AfterEach
    void cleanOnlyTestRecords() {
        for (String client : clients) {
            jdbc.update("DELETE FROM gps_device_track WHERE client_id=?", client);
            jdbc.update("DELETE FROM gps_device_location WHERE client_id=?", client);
            jdbc.update("DELETE FROM gps_device WHERE client_id=?", client);
        }
    }

    @Test
    void savesDeviceAndPreservesMetadataOnAuthenticationAndHeartbeat() {
        String client = client();
        service.saveDevice(new DeviceDO().setMobileNo(client).setDeviceId("INTEGRATION_TEST")
                .setPlateNo("测试粤B001").setProtocolVersion(1));
        service.saveDevice(new DeviceDO().setMobileNo(client).setProtocolVersion(1));
        service.touchDevice(position(client, time, 22781610));
        assertEquals("INTEGRATION_TEST", jdbc.queryForObject(
                "SELECT device_id FROM gps_device WHERE client_id=?", String.class, client));
        assertEquals("测试粤B001", jdbc.queryForObject(
                "SELECT plate_no FROM gps_device WHERE client_id=?", String.class, client));
    }

    @Test
    void savesHistoryDeduplicatesRetransmissionAndPreventsOlderPositionReplacingLatest() {
        String client = client();
        T0200 latest = position(client, time, 22781610);
        service.saveLocations(List.of(latest));
        latest.setSerialNo(99);
        service.saveLocations(List.of(latest));
        service.saveLocations(List.of(position(client, time.minusMinutes(1), 22781000)));
        assertEquals(2, count("gps_device_track", client));
        assertEquals(1, count("gps_device_location", client));
        assertEquals(new BigDecimal("22.781610"), jdbc.queryForObject(
                "SELECT latitude FROM gps_device_location WHERE client_id=?", BigDecimal.class, client));
        assertEquals(time, jdbc.queryForObject(
                "SELECT device_time FROM gps_device_location WHERE client_id=?", LocalDateTime.class, client));
        assertEquals(new BigDecimal("11.1"), jdbc.queryForObject(
                "SELECT speed FROM gps_device_location WHERE client_id=?", BigDecimal.class, client));
    }

    @Test
    void preservesDistinctReportsInTheSameSecond() {
        String client = client();
        service.saveLocations(List.of(position(client, time, 22781610), position(client, time, 22781611)));
        assertEquals(2, count("gps_device_track", client));
    }

    @Test
    void storesSouthernWesternCoordinatesAndUnsignedStatusFlags() {
        String client = client();
        T0200 point = position(client, time, 22781610);
        point.setStatusBit(0x8000000E);
        point.setWarnBit(0x80000000);
        service.saveLocations(List.of(point));
        assertEquals(new BigDecimal("-22.781610"), jdbc.queryForObject(
                "SELECT latitude FROM gps_device_location WHERE client_id=?", BigDecimal.class, client));
        assertEquals(new BigDecimal("-113.880928"), jdbc.queryForObject(
                "SELECT longitude FROM gps_device_location WHERE client_id=?", BigDecimal.class, client));
        assertEquals(2147483662L, jdbc.queryForObject(
                "SELECT status_bit FROM gps_device_location WHERE client_id=?", Long.class, client).longValue());
        assertEquals(2147483648L, jdbc.queryForObject(
                "SELECT warn_bit FROM gps_device_location WHERE client_id=?", Long.class, client).longValue());
    }

    @Test
    void batchUploadUsesParentTerminalIdentityAndRetainsHistoricalTime() {
        String client = client();
        T0200 first = position(null, time, 22781610);
        T0200 second = position(null, time.minusHours(1), 22781000);
        T0704 batch = new T0704();
        batch.setClientId(client);
        batch.setMessageId(0x0704);
        batch.setProtocolVersion(1);
        batch.setTotal(2);
        batch.setType(1);
        batch.setItems(List.of(first, second));
        service.saveBatchUpload(batch);
        assertEquals(2, count("gps_device_track", client));
        assertEquals(time, jdbc.queryForObject(
                "SELECT device_time FROM gps_device_location WHERE client_id=?", LocalDateTime.class, client));
    }

    @Test
    void rollsBackTheWholeBatchWhenDatabaseWriteFails() {
        String firstClient = client();
        String secondClient = client();
        T0200 first = position(firstClient, time, 22781610);
        T0200 second = position(secondClient, time, 22781610);
        Session session = mock(Session.class);
        when(session.getAttribute(SessionKey.Device)).thenReturn(new DeviceDO().setPlateNo("X".repeat(150)));
        second.setSession(session);
        assertThrows(DataAccessException.class, () -> service.saveLocations(List.of(first, second)));
        assertEquals(0, count("gps_device", firstClient));
        assertEquals(0, count("gps_device", secondClient));
        assertEquals(0, count("gps_device_track", firstClient));
    }

    @Test
    void rejectsInvalidLocationAndBadChecksumWithoutWritingRows() {
        String client = client();
        T0200 bad = position(client, time, 91000000);
        assertThrows(IllegalArgumentException.class, () -> service.saveLocations(List.of(bad)));
        bad.setLatitude(22781610);
        bad.setVerified(false);
        assertThrows(IllegalArgumentException.class, () -> service.saveLocations(List.of(bad)));
        assertEquals(0, count("gps_device", client));
    }

    @Test
    void queriesOfflineDeviceSnapshotAndStableTrackPagesIncludingSameSecondReports() {
        String client = client();
        service.saveDevice(new DeviceDO().setMobileNo(client).setDeviceId("APP_TEST").setPlateNo("测试车牌"));
        service.saveLocations(List.of(position(client, time, 22781610),
                position(client, time, 22781611), position(client, time.plusMinutes(1), 22781612)));
        var latest = queries.latest(client).orElseThrow();
        assertEquals(new BigDecimal("22.781612"), latest.latitude());
        assertEquals(3L, latest.revision());
        var devices = queries.devices(client, 1);
        // 离线状态来自会话；设备资料在数据库中仍存在。
        var device = queries.devices(null, 500).items().stream().filter(d -> d.clientId().equals(client)).findFirst().orElseThrow();
        assertFalse(device.online());
        assertEquals("APP_TEST", device.deviceId());
        var page1 = queries.tracks(client, time, time.plusHours(1), null, 1);
        var page2 = queries.tracks(client, time, time.plusHours(1), page1.nextCursor(), 1);
        var page3 = queries.tracks(client, time, time.plusHours(1), page2.nextCursor(), 1);
        assertTrue(page1.hasMore());
        assertTrue(page2.hasMore());
        assertFalse(page3.hasMore());
        assertNotEquals(page1.items().get(0).trackId(), page2.items().get(0).trackId());
        assertEquals(time, page2.items().get(0).deviceTime());
        assertEquals(1, queries.tracks(client, time.plusMinutes(1), time.plusMinutes(2), null, 100).items().size());
        String missing = client();
        assertEquals(List.of(missing), queries.snapshot(List.of(client, missing)).missingClientIds());
        assertTrue(queries.latest(missing).isEmpty());
    }

    @Test
    void rejectsUnboundedOrInvalidQueriesAndCrossDeviceCursor() {
        String client = client();
        String other = client();
        service.saveLocations(List.of(position(client, time, 22781610), position(client, time.plusSeconds(1), 22781611)));
        String cursor = queries.tracks(client, time, time.plusHours(1), null, 1).nextCursor();
        assertThrows(IllegalArgumentException.class, () -> queries.devices(null, 501));
        assertThrows(IllegalArgumentException.class, () -> queries.latest("' OR 1=1"));
        assertThrows(IllegalArgumentException.class, () -> queries.snapshot(List.of()));
        assertThrows(IllegalArgumentException.class, () -> queries.tracks(client, time, time.plusDays(32), null, 100));
        assertThrows(IllegalArgumentException.class, () -> queries.tracks(client, time, time, null, 100));
        assertThrows(IllegalArgumentException.class, () -> queries.tracks(other, time, time.plusHours(1), cursor, 100));
        assertThrows(IllegalArgumentException.class, () -> queries.tracks(client, time, time.plusHours(1), "bad", 100));
    }

    @Test
    void subscriptionOnlyDeliversCommittedPositionsAndSuppressesRetransmissionAndOlderReports() throws Exception {
        String client = client();
        var received = new java.util.concurrent.LinkedBlockingQueue<org.springframework.http.codec.ServerSentEvent<Object>>();
        var connection = subscriptions.subscribe(List.of(client)).subscribe(received::add);
        try {
            assertEquals("snapshot", received.poll(5, java.util.concurrent.TimeUnit.SECONDS).event());
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
            tx.executeWithoutResult(status -> {
                service.saveLocations(List.of(position(client, time, 22781610)));
                assertNull(received.poll());
                status.setRollbackOnly();
            });
            assertNull(received.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS));
            assertTrue(queries.latest(client).isEmpty());
            tx.executeWithoutResult(status -> {
                service.saveLocations(List.of(position(client, time, 22781610)));
                assertNull(received.poll());
            });
            var update = received.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("location", update.event());
            assertEquals(1L, ((org.yzh.web.model.vo.GpsLocation) update.data()).revision());
            service.saveLocations(List.of(position(client, time, 22781610)));
            service.saveLocations(List.of(position(client, time.minusMinutes(1), 22781000)));
            assertNull(received.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            service.saveLocations(List.of(position(client, time.plusMinutes(1), 22781611)));
            assertEquals(2L, ((org.yzh.web.model.vo.GpsLocation) received.poll(5, java.util.concurrent.TimeUnit.SECONDS).data()).revision());
        } finally {
            connection.dispose();
        }
        assertEquals(0, subscriptions.subscriptionCount());
    }

    private String client() {
        String client = "98" + String.format("%018d", Math.abs(UUID.randomUUID().getLeastSignificantBits()
                % 1_000_000_000_000_000_000L));
        clients.add(client);
        return client;
    }

    private T0200 position(String client, LocalDateTime deviceTime, int latitude) {
        T0200 point = new T0200();
        point.setClientId(client);
        point.setMessageId(0x0200);
        point.setProtocolVersion(1);
        point.setDeviceTime(deviceTime);
        point.setLatitude(latitude);
        point.setLongitude(113880928);
        point.setStatusBit(2050);
        point.setSpeed(111);
        return point;
    }

    private long count(String table, String client) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE client_id=?", Long.class, client);
    }

    @Configuration
    @EnableTransactionManagement
    @Import({GpsPersistenceConfig.class, GpsPersistenceService.class, GpsQueryService.class, GpsSubscriptionService.class})
    static class TestConfig {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource("application.yml"));
            Properties properties = yaml.getObject();
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(properties.getProperty("spring.datasource.url"));
            dataSource.setUsername(properties.getProperty("spring.datasource.username"));
            dataSource.setPassword(properties.getProperty("spring.datasource.password"));
            dataSource.setMaximumPoolSize(3);
            dataSource.setConnectionTimeout(5000);
            return dataSource;
        }

        @Bean io.github.yezhihao.netmc.session.SessionManager sessionManager() {
            return mock(io.github.yezhihao.netmc.session.SessionManager.class);
        }

        @Bean JdbcTemplate jdbcTemplate(HikariDataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean org.springframework.jdbc.datasource.DataSourceTransactionManager transactionManager(
                HikariDataSource dataSource) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        }
    }
}
