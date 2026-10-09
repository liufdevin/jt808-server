package org.yzh.web.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.yzh.web.model.vo.GpsLocation;
import org.yzh.web.model.vo.GpsSnapshot;
import reactor.core.publisher.BaseSubscriber;
import org.reactivestreams.Subscription;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GpsSubscriptionServiceTest {
    private static final String CLIENT = "10000000001";
    private final GpsQueryService queries = mock(GpsQueryService.class);
    private final GpsSubscriptionService service = new GpsSubscriptionService(queries);

    @Test
    void avoidsSnapshotRaceAndFiltersOlderOrUnsubscribedDeviceEvents() throws Exception {
        when(queries.snapshot(List.of(CLIENT))).thenAnswer(call -> {
            service.afterCommit(new GpsLocationsCommitted(List.of(point(CLIENT, 1))));
            return new GpsSnapshot(List.of(point(CLIENT, 2)), List.of());
        });
        BlockingQueue<ServerSentEvent<Object>> received = new LinkedBlockingQueue<>();
        var connection = service.subscribe(List.of(CLIENT)).subscribe(received::add);
        try {
            assertEquals("snapshot", received.poll(5, TimeUnit.SECONDS).event());
            service.afterCommit(new GpsLocationsCommitted(List.of(point(CLIENT, 2), point("999", 3), point(CLIENT, 3))));
            assertEquals(3L, ((GpsLocation) received.poll(5, TimeUnit.SECONDS).data()).revision());
            service.afterCommit(new GpsLocationsCommitted(List.of(point(CLIENT, 2))));
            assertNull(received.poll(150, TimeUnit.MILLISECONDS));
        } finally { connection.dispose(); }
        assertEquals(0, service.subscriptionCount());
        verify(queries, times(1)).snapshot(List.of(CLIENT));
    }

    @Test
    void independentConnectionsReceiveSamePositionAndDisposeIndependently() throws Exception {
        when(queries.snapshot(List.of(CLIENT))).thenReturn(new GpsSnapshot(List.of(), List.of(CLIENT)));
        BlockingQueue<ServerSentEvent<Object>> first = new LinkedBlockingQueue<>(), second = new LinkedBlockingQueue<>();
        var one = service.subscribe(List.of(CLIENT)).subscribe(first::add);
        var two = service.subscribe(List.of(CLIENT)).subscribe(second::add);
        try {
            assertEquals("snapshot", first.poll(5, TimeUnit.SECONDS).event());
            assertEquals("snapshot", second.poll(5, TimeUnit.SECONDS).event());
            one.dispose();
            service.afterCommit(new GpsLocationsCommitted(List.of(point(CLIENT, 1))));
            assertEquals("location", second.poll(5, TimeUnit.SECONDS).event());
            assertNull(first.poll());
            assertEquals(1, service.subscriptionCount());
        } finally { one.dispose(); two.dispose(); }
        assertEquals(0, service.subscriptionCount());
    }

    @Test
    void cleansUpWhenInitialDatabaseReadFails() throws Exception {
        when(queries.snapshot(List.of(CLIENT))).thenThrow(new IllegalStateException("DB unavailable"));
        CountDownLatch failed = new CountDownLatch(1);
        service.subscribe(List.of(CLIENT)).subscribe(value -> fail("unexpected event"), error -> failed.countDown());
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 100 && service.subscriptionCount() != 0; i++) Thread.sleep(10);
        assertEquals(0, service.subscriptionCount());
    }

    @Test
    void slowConnectionHasBoundedBufferAndEndsSoItCanReconnect() throws Exception {
        when(queries.snapshot(List.of(CLIENT))).thenReturn(new GpsSnapshot(List.of(), List.of(CLIENT)));
        CountDownLatch snapshot = new CountDownLatch(1), ended = new CountDownLatch(1);
        BaseSubscriber<ServerSentEvent<Object>> slow = new BaseSubscriber<>() {
            protected void hookOnSubscribe(Subscription subscription) { request(1); }
            protected void hookOnNext(ServerSentEvent<Object> value) { if (value.event().equals("snapshot")) snapshot.countDown(); }
            protected void hookOnComplete() { ended.countDown(); }
        };
        service.subscribe(List.of(CLIENT)).subscribe(slow);
        try {
            assertTrue(snapshot.await(5, TimeUnit.SECONDS));
            for (int i = 1; i <= 1000; i++) service.afterCommit(new GpsLocationsCommitted(List.of(point(CLIENT, i))));
            slow.request(2000);
            assertTrue(ended.await(5, TimeUnit.SECONDS));
        } finally { slow.dispose(); }
        assertEquals(0, service.subscriptionCount());
    }

    private GpsLocation point(String client, long revision) {
        return new GpsLocation(client, null, revision, LocalDateTime.of(2026, 10, 9, 10, 0),
                LocalDateTime.of(2026, 10, 9, 10, 0), new BigDecimal("22.781610"),
                new BigDecimal("113.880928"), 10, new BigDecimal("11.1"), 90, 0, 2, true,
                "UNKNOWN", Map.of(), 1, 0x0200, 1);
    }
}
