# APP 定位查询与订阅接口

服务端 HTTP 端口默认 `8100`，定位器 TCP/UDP 端口默认 `51150`。APP 请求 HTTP 端口。
部署仍只需要一个 `jtt808-server-1.0.0-SNAPSHOT.jar`；数据库配置、建表与旧表补列逻辑都在 JAR 中。

以下示例设备编号为 `10000000001`。设备编号必须作为字符串处理，保留前导零。

## 设备列表

```http
GET /api/gps/devices?limit=100
GET /api/gps/devices?limit=100&afterClientId=10000000001
```

响应格式为 `{"code":200,"data":{"items":[...],"hasMore":false}}`。有下一页时返回 `nextCursor`，
将它用作下一次请求的 `afterClientId`。`limit` 为 1～500，默认100，按设备编号升序分页。
设备资料从 `gps_device` 查询，包含离线设备；`online` 表示当前服务器是否有该设备已注册的连接，
`lastSeenAt` 为最后一次成功记录的通信时间。设备字段包括 `clientId`、`deviceId`、`plateNo`、`protocolVersion`、`createdAt`。
原有 `/device/option` 和 `/device/all` 仍用于在线会话查询。

## 最新位置

```http
GET /api/gps/devices/10000000001/location
GET /api/gps/locations?clientIds=10000000001,10000000002
```

单设备从 `gps_device_location` 查询。尚无定位时返回 HTTP 404（响应 `code:404`）。
批量接口一次指定1～100个编号，返回 `data.locations` 和没有定位的 `data.missingClientIds`。
设备离线或服务器重启，不影响已保存位置的查询。

位置数据示例：

```json
{
  "clientId": "10000000001",
  "revision": "1",
  "deviceTime": "2026-10-09 10:00:00",
  "receivedTime": "2026-10-09 10:00:01",
  "latitude": 22.781610,
  "longitude": 113.880928,
  "altitude": 10,
  "speed": 11.1,
  "direction": 90,
  "warnBit": "0",
  "statusBit": "2050",
  "positioned": true,
  "coordinateSystem": "GCJ02",
  "attributes": {},
  "protocolVersion": 1,
  "messageId": 512,
  "serialNo": 1
}
```

经纬度单位为度、速度 km/h、海拔 m、方向为度。`warnBit`、`statusBit` 是 JT808 位标志。
日期响应沿用项目格式 `yyyy-MM-dd HH:mm:ss`，设备定位时间不带时区，本项目设备按北京时间使用。
JSON 的 Long 字段（`revision`、`trackId`、位标志等）沿用项目设置，返回字符串，APP 应避免转换为不精确的浮点数。
未定位的数据仍会保存，APP 可根据 `positioned` 判断是否适合展示为当前位置。

坐标不自动转换；`coordinateSystem` 来自 `application.yml` 的 `jt-server.persistence.coordinate-system`。
当前 ClientTest 模拟器联调配置为 `GCJ02`。接入真实设备前，确认其实际坐标系并配置 `WGS84`、`GCJ02` 或 `BD09`，修改后重新打包。
模拟器的合水口地铁站坐标为 GCJ02；高德地图展示其他坐标系的数据时需要先转换。
修改配置不会重写数据库已有位置的坐标系标签；部署新 JAR 并重启后，需要模拟器重新上报位置，最新位置和后续轨迹才会以 GCJ02 保存。

## 历史轨迹

```http
GET /api/gps/devices/10000000001/tracks?from=2026-10-09T00:00:00&to=2026-10-10T00:00:00&limit=500
```

从 `gps_device_track` 查询，范围为 `[from,to)`，按定位时间、轨迹记录编号升序。
时间参数使用 ISO 日期时间（中间的 `T`），单次范围最多31天。`limit` 为1～1000，默认500。
返回 `data.items`、`data.hasMore`，还有下一页时返回 `data.nextCursor`。
下一页保持设备编号、from、to 不变，将 nextCursor 原样传入 `cursor` 参数，不要解析或修改它。
同一秒内不同的定位点也可完整分页，不会因时间相同被遗漏。
轨迹点包含 `trackId`，不包含最新位置的 `revision`。
参数非法返回 HTTP 400；查询范围内没有记录时返回空列表。

定位器补报的数据按原定位时间排序。正在查询时新补报的旧点可能落在已经读取的页面范围内；
查询完成后需要刷新该时间范围才能看到这些点。APP 补取断线期间轨迹时，建议查询带重叠的时间范围，
用 `trackId` 去重，并以查询结果重建该段轨迹。

## 定位订阅（SSE）

```http
GET /api/gps/locations/subscribe?clientIds=10000000001,10000000002
Accept: text/event-stream
```

一次连接订阅1～100台设备。多个 APP 可同时订阅同一台设备，各自断开互不影响。
新的订阅与协议监控 `/sse/connect` 分开；APP 使用上面的结构化定位订阅接口。

事件类型：

- `snapshot`：连接时从数据库读取最新位置，data 包含 `locations`、`missingClientIds`，没有定位也会发送。
- `location`：定位事务成功提交后推送该设备最终的最新位置，data 为一个位置对象。
- `heartbeat`：每20秒发送一次，data 为 `{"time":"毫秒时间戳"}`，用于保持长连接。

示例：

```text
event:snapshot
retry:3000
data:{"locations":[],"missingClientIds":["10000000001"]}

id:10000000001:1
event:location
data:{"clientId":"10000000001","revision":"1","latitude":22.781610,"longitude":113.880928,...}
```

APP 收到 snapshot 后先初始化设备位置，再应用 location。每台设备只应用更大的 `revision`；
建议按字符串精确转为64位整数或 BigInt 比较。服务端也会过滤较旧版本。
重复定位报文和旧定位补报不重复触发最新位置更新；旧点仍写入轨迹表。
数据库事务失败或回滚时不发布更新。一次入库操作仅按设备批量读取最新位置，再分发给所有订阅者，
不会因为订阅手机增多而对每个手机重新查询数据库。

断线重连时会再次发送数据库快照，包括设备离线及服务器重启后的最后位置。
该流是最新位置通知，不是每个轨迹点的可靠回放；`Last-Event-ID` 不用于历史回放。
要补取遗漏的轨迹，使用历史轨迹接口。慢连接使用每连接256条的有界队列，队列满会结束连接，
APP 应重连并读取新的快照。原生手机 SSE 客户端需要自行处理重连（建议约3秒并退避）；
浏览器 EventSource 接收 retry:3000 后通常自动重连。

浏览器示例（同源）：

```javascript
const source = new EventSource('/api/gps/locations/subscribe?clientIds=10000000001');
const revisions = new Map();
function apply(point) {
  const version = BigInt(point.revision);
  if (version <= (revisions.get(point.clientId) ?? -1n)) return;
  revisions.set(point.clientId, version);
  // 更新地图标记：point.longitude / point.latitude
}
source.addEventListener('snapshot', event => {
  revisions.clear();
  const snapshot = JSON.parse(event.data);
  snapshot.locations.forEach(apply);
  // missingClientIds 对应设备暂无位置；清除本地旧标记。
});
source.addEventListener('location', event => apply(JSON.parse(event.data)));
// 页面退出时 source.close();
```

反向代理需关闭订阅接口的响应缓冲，读取超时应超过20秒心跳间隔，例如 Nginx：

```nginx
location /api/gps/locations/subscribe {
    proxy_pass http://127.0.0.1:8100;
    proxy_buffering off;
    proxy_read_timeout 60s;
}
```

这些接口沿用当前项目的访问控制方式，目前未实现 APP 账号登录与账号/设备绑定。
登录和设备权限需在接入正式 APP 时结合实际用户体系补充；客户端传入设备编号只表示查询或订阅对象，不表示访问授权。
实时推送连接由当前 JVM 管理，单服务部署可直接使用；多个服务实例部署时，查询数据可共享 MySQL，
跨实例的实时推送还需要共享消息通道（例如 Redis Pub/Sub），本次没有引入该依赖。

## 验证与打包

```powershell
mvn clean package
```

需要显式运行真实 MySQL 集成测试时：

```powershell
$env:GPS_MYSQL_TEST='true'
try {
    mvn '-Dmaven.test.skip=false' '-Dtest=GpsPersistenceMySqlTest,GpsSubscriptionServiceTest,SSEDisconnectExceptionResolverTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
} finally {
    Remove-Item Env:GPS_MYSQL_TEST -ErrorAction SilentlyContinue
}
```

测试仅清理自己生成的随机设备记录。启动时只创建缺少的表或补充最新位置的 `revision`、`report_hash` 两列，不清空已有数据。
若关闭 `initialize-schema`，需提前完成表结构升级。替换 JAR 后执行：

```bash
java -jar jtt808-server-1.0.0-SNAPSHOT.jar
```
