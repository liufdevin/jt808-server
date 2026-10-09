package org.yzh.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.yzh.commons.model.R;
import org.yzh.web.model.vo.*;
import org.yzh.web.service.GpsQueryService;
import org.yzh.web.service.GpsSubscriptionService;
import reactor.core.publisher.Flux;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/gps")
@RequiredArgsConstructor
public class GpsController {
    private final GpsQueryService queries;
    private final GpsSubscriptionService subscriptions;

    @Operation(summary = "数据库设备列表，包含离线设备；nextCursor 用作下一页 afterClientId")
    @GetMapping("/devices")
    public R<GpsPage<GpsDevice>> devices(@RequestParam(required = false) String afterClientId,
                                        @RequestParam(defaultValue = "100") int limit) {
        return R.success(queries.devices(afterClientId, limit));
    }

    @Operation(summary = "从数据库查询设备最新位置")
    @GetMapping("/devices/{clientId}/location")
    public ResponseEntity<R<GpsLocation>> location(@PathVariable String clientId) {
        return queries.latest(clientId).map(point -> ResponseEntity.ok(R.success(point)))
                .orElseGet(() -> ResponseEntity.status(404).body(R.error(404, "该设备尚无定位数据")));
    }

    @Operation(summary = "批量查询最新位置，clientIds 为逗号分隔的1到100个设备编号")
    @GetMapping("/locations")
    public R<GpsSnapshot> locations(@RequestParam List<String> clientIds) {
        return R.success(queries.snapshot(clientIds));
    }

    @Operation(summary = "按定位时间升序分页查询轨迹；时间范围为[from,to)，最多31天")
    @GetMapping("/devices/{clientId}/tracks")
    public R<GpsPage<GpsLocation>> tracks(@PathVariable String clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "500") int limit) {
        return R.success(queries.tracks(clientId, from, to, cursor, limit));
    }

    @Operation(summary = "APP 定位订阅，先发送数据库快照，再发送已提交的定位更新")
    @GetMapping(value = "/locations/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> subscribe(@RequestParam List<String> clientIds,
                                                   HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return subscriptions.subscribe(clientIds);
    }
}
