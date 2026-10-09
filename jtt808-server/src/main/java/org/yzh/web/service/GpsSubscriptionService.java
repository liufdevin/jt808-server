package org.yzh.web.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.yzh.web.model.vo.GpsLocation;
import org.yzh.web.model.vo.GpsSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class GpsSubscriptionService {
    private final GpsQueryService queries;
    private final Map<String, Set<Connection>> subscriptions = new ConcurrentHashMap<>();

    public Flux<ServerSentEvent<Object>> subscribe(List<String> clientIds) {
        List<String> ids = GpsQueryService.clientIds(clientIds);
        return Flux.defer(() -> {
            Connection connection = new Connection();
            // 先登记再读数据库，连接建立期间的提交进入有界队列，避免快照/订阅之间漏更新。
            ids.forEach(id -> subscriptions.compute(id, (key, connections) -> {
                if (connections == null) connections = ConcurrentHashMap.newKeySet();
                connections.add(connection);
                return connections;
            }));
            Flux<ServerSentEvent<Object>> data = Mono.fromCallable(() -> queries.snapshot(ids))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(snapshot -> {
                        snapshot.locations().forEach(point -> connection.versions.put(point.clientId(), point.revision()));
                        Flux<ServerSentEvent<Object>> live = connection.sink.asFlux()
                                .publishOn(Schedulers.boundedElastic(), 1)
                                .filter(point -> {
                                    long previous = connection.versions.getOrDefault(point.clientId(), -1L);
                                    if (point.revision() <= previous) return false;
                                    connection.versions.put(point.clientId(), point.revision());
                                    return true;
                                }).map(point -> ServerSentEvent.<Object>builder(point).event("location")
                                        .id(point.clientId() + ":" + point.revision()).build());
                        return Flux.concat(Flux.just(ServerSentEvent.<Object>builder(snapshot)
                                .event("snapshot").retry(Duration.ofSeconds(3)).build()), live);
                    });
            Flux<ServerSentEvent<Object>> heartbeat = Flux.interval(Duration.ofSeconds(20))
                    .onBackpressureDrop().map(tick -> ServerSentEvent.<Object>builder()
                            .event("heartbeat").data(Map.of("time", System.currentTimeMillis())).build());
            // 心跳随数据流一起结束，慢连接队列满时关闭，APP 重连后重新获取数据库快照。
            return data.publish(shared -> shared.mergeWith(heartbeat.takeUntilOther(shared.ignoreElements())))
                    .doFinally(signal -> ids.forEach(id -> subscriptions.computeIfPresent(id, (key, connections) -> {
                        connections.remove(connection);
                        return connections.isEmpty() ? null : connections;
                    })));
        });
    }

    @TransactionalEventListener
    public void afterCommit(GpsLocationsCommitted event) {
        for (GpsLocation point : event.locations()) {
            Set<Connection> connections = subscriptions.get(point.clientId());
            if (connections != null) connections.forEach(connection -> connection.offer(point));
        }
    }

    int subscriptionCount() {
        return subscriptions.values().stream().mapToInt(Set::size).sum();
    }

    private static class Connection {
        final Map<String, Long> versions = new HashMap<>();
        final Sinks.Many<GpsLocation> sink = Sinks.many().unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(256));

        synchronized void offer(GpsLocation point) {
            if (sink.tryEmitNext(point) == Sinks.EmitResult.FAIL_OVERFLOW) sink.tryEmitComplete();
        }
    }
}
