package org.yzh.client;

import org.yzh.client.netty.ClientConfig;
import org.yzh.client.netty.HandlerMapping;
import org.yzh.client.netty.TCPClient;
import org.yzh.commons.util.StrUtils;
import org.yzh.protocol.codec.JTMessageAdapter;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.commons.transform.AttributeKey;
import org.yzh.protocol.commons.transform.attribute.AlarmADAS;
import org.yzh.protocol.commons.transform.attribute.AlarmBSD;
import org.yzh.protocol.commons.transform.attribute.AlarmDSM;
import org.yzh.protocol.commons.transform.attribute.AlarmTPMS;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0200;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

public class ClientTest {

    // 深圳市光明区合水口地铁站，高德 GCJ-02 坐标，供地图联调使用。
    // JT808 经纬度字段为度数乘以 1,000,000；此测试坐标无需再转换为高德坐标。
    private static final int TEST_LATITUDE = 22781610;   // 北纬 22.781610
    private static final int TEST_LONGITUDE = 113880928; // 东经 113.880928

    private static final long REPORT_INTERVAL_MS = 3000L;
    private static final int REPORT_COUNT = 1000;

    // 合水口地铁站附近的示意闭环路线，并非导航规划的真实道路。
    // 每个点依次为：经度×1,000,000、纬度×1,000,000、驶向下一点的速度(km/h)。
    // 路段之间插值，每3秒移动约15～27米；跑完一圈会连续进入下一圈。
    private static final int[][] DRIVING_ROUTE = {
            {TEST_LONGITUDE, TEST_LATITUDE, 24},
            {113882200, 22781640, 30},
            {113883800, 22781690, 24},
            {113885200, 22781820, 18},
            {113885500, 22783000, 28},
            {113885520, 22784300, 18},
            {113884200, 22784520, 32},
            {113882600, 22784550, 26},
            {113881000, 22784480, 18},
            {113880600, 22783500, 28},
            {113880620, 22782300, 18},
            {113880750, 22781850, 18}
    };
    private static final DrivingSimulator simulator = new DrivingSimulator();

    public static final JTMessageAdapter messageAdapter = new JTMessageAdapter("org.yzh.protocol");

    public static final ClientConfig jtConfig = new ClientConfig.Builder()
            .setIp("down.szbaijie.com")
            .setPort(51150)
            .setMaxFrameLength(2 + 21 + 1023 * 2 + 1 + 2)
            .setDelimiters(new byte[]{0x7e})
            .setDecoder(messageAdapter)
            .setEncoder(messageAdapter)
            .setHandlerMapping(new HandlerMapping("org.yzh.client"))
            .build();

    public static void main(String[] args) {
        TCPClient tcpClient = new TCPClient("0", jtConfig).start();
        try {
            tcpClient.writeObject(T0100("1"));
            long previousReport = System.nanoTime();
            for (int i = 0; i < REPORT_COUNT; i++) {
                Thread.sleep(REPORT_INTERVAL_MS);
                long now = System.nanoTime();
                simulator.advance((now - previousReport) / 1_000_000_000.0);
                previousReport = now;
                boolean alarm = i % 10 == 0;
                T0200 message = T0200("1", alarm);
                message.setSerialNo(i + 1);
                tcpClient.writeObject(message);
                System.out.printf(Locale.ROOT,
                        "模拟行驶：第%d条，经度=%.6f，纬度=%.6f，速度=%.1fkm/h，方向=%d°%n",
                        i + 1, message.getLongitude() / 1_000_000.0, message.getLatitude() / 1_000_000.0,
                        message.getSpeed() / 10.0, message.getDirection());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tcpClient.stop();
        }
    }

    public static T0100 T0100(String id) {
        String deviceId = "T" + StrUtils.leftPad(id, 6, '0');
        String clientId = "1" + StrUtils.leftPad(id, 10, '0');
        String plateNo = "测A" + StrUtils.leftPad(id, 5, '0');

        T0100 message = new T0100();
        message.setMessageId(JT808.终端注册);
        message.setProtocolVersion(1);
        message.setVersion(true);
        message.setClientId(clientId);

        message.setProvinceId(31);
        message.setCityId(115);
        message.setMakerId("yzh");
        message.setDeviceModel("www.jtt808.cn");
        message.setDeviceId(deviceId);
        message.setPlateColor(1);
        message.setPlateNo(plateNo);
        return message;
    }

    private static final ThreadLocalRandom random = ThreadLocalRandom.current();

    public static T0200 T0200(String id) {
        return T0200(id, true);
    }

    public static T0200 T0200(String id, boolean alarm) {
        String clientId = "1" + StrUtils.leftPad(id, 10, '0');

        T0200 message = new T0200();
        message.setMessageId(JT808.位置信息汇报);
        message.setProtocolVersion(1);
        message.setVersion(true);
        message.setClientId(clientId);

        DrivingPosition position = simulator.position();
        message.setWarnBit(0);
        message.setStatusBit(0x03); // ACC开启，已经定位。
        message.setLatitude(position.latitude());
        message.setLongitude(position.longitude());
        message.setAltitude(10);
        message.setSpeed(position.speed()); // JT808 速度单位为0.1km/h。
        message.setDirection(position.direction());
        LocalDateTime dateTime = LocalDateTime.now();
        message.setDeviceTime(dateTime);

        Map<Integer, Object> attributes = new TreeMap<>();
        attributes.put(AttributeKey.Mileage, position.mileage()); // JT808 里程单位为0.1km。
        if (alarm) {
            AlarmADAS alarmADAS = new AlarmADAS();
            alarmADAS.setId(64);
            alarmADAS.setState(1);
            alarmADAS.setType(random.nextInt(1, 11));
            alarmADAS.setLevel(1);
            alarmADAS.setFrontSpeed(10);
            alarmADAS.setFrontDistance(10);
            alarmADAS.setDeviateType(1);
            alarmADAS.setRoadSign(1);
            alarmADAS.setRoadSignValue(10);
            alarmADAS.setSpeed((int) Math.round(message.getSpeed() / 10.0));
            alarmADAS.setAltitude(message.getAltitude());
            alarmADAS.setLatitude(message.getLatitude());
            alarmADAS.setLongitude(message.getLongitude());
            alarmADAS.setAlarmTime(dateTime);
            alarmADAS.setStatusBit(1);
            alarmADAS.setDeviceId(clientId);
            alarmADAS.setDateTime(dateTime);
            alarmADAS.setSequenceNo(1);
            alarmADAS.setFileTotal(1);
            alarmADAS.setReserved(1);

            AlarmDSM alarmDSM = new AlarmDSM();
            alarmDSM.setId(65);
            alarmDSM.setState(2);
            alarmDSM.setType(random.nextInt(1, 17));
            alarmDSM.setLevel(2);
            alarmDSM.setFatigueDegree(20);
            alarmDSM.setReserved(20);
            alarmDSM.setSpeed((int) Math.round(message.getSpeed() / 10.0));
            alarmDSM.setAltitude(message.getAltitude());
            alarmDSM.setLatitude(message.getLatitude());
            alarmDSM.setLongitude(message.getLongitude());
            alarmDSM.setAlarmTime(dateTime);
            alarmDSM.setStatusBit(2);
            alarmDSM.setDeviceId(clientId);
            alarmDSM.setDateTime(dateTime);
            alarmDSM.setSequenceNo(2);
            alarmDSM.setFileTotal(2);
            alarmDSM.setReserved(2);

            AlarmTPMS alarmTPMS = new AlarmTPMS();
            alarmTPMS.setId(66);
            alarmTPMS.setState(3);
            alarmTPMS.setSpeed((int) Math.round(message.getSpeed() / 10.0));
            alarmTPMS.setAltitude(message.getAltitude());
            alarmTPMS.setLatitude(message.getLatitude());
            alarmTPMS.setLongitude(message.getLongitude());
            alarmTPMS.setAlarmTime(dateTime);
            alarmTPMS.setStatusBit(3);
            alarmTPMS.setDeviceId(clientId);
            alarmTPMS.setDateTime(dateTime);
            alarmTPMS.setSequenceNo(3);
            alarmTPMS.setFileTotal(3);
            alarmTPMS.setReserved(3);

            AlarmBSD alarmBSD = new AlarmBSD();
            alarmBSD.setId(67);
            alarmBSD.setState(4);
            alarmBSD.setType(random.nextInt(1, 3));
            alarmBSD.setSpeed((int) Math.round(message.getSpeed() / 10.0));
            alarmBSD.setAltitude(message.getAltitude());
            alarmBSD.setLatitude(message.getLatitude());
            alarmBSD.setLongitude(message.getLongitude());
            alarmBSD.setAlarmTime(dateTime);
            alarmBSD.setStatusBit(4);
            alarmBSD.setDeviceId(clientId);
            alarmBSD.setDateTime(dateTime);
            alarmBSD.setSequenceNo(4);
            alarmBSD.setFileTotal(4);
            alarmBSD.setReserved(4);


            attributes.put(AlarmADAS.key, alarmADAS);
            attributes.put(AlarmDSM.key, alarmDSM);
            attributes.put(AlarmTPMS.key, alarmTPMS);
            attributes.put(AlarmBSD.key, alarmBSD);

        }
        message.setAttributes(attributes);
        return message;
    }

    private record DrivingPosition(int latitude, int longitude, int speed, int direction, long mileage) { }

    /** 按路段速度消耗行驶时间，主循环和平台位置查询共用同一份车辆位置。 */
    private static final class DrivingSimulator {
        private static final double EARTH_RADIUS_METERS = 6_371_000;
        private final double[] segmentLengths = new double[DRIVING_ROUTE.length];
        private final int[] directions = new int[DRIVING_ROUTE.length];
        private int segment;
        private double distanceOnSegment;
        private double totalDistance;

        private DrivingSimulator() {
            for (int i = 0; i < DRIVING_ROUTE.length; i++) {
                int[] from = DRIVING_ROUTE[i];
                int[] to = DRIVING_ROUTE[(i + 1) % DRIVING_ROUTE.length];
                double lat1 = Math.toRadians(from[1] / 1_000_000.0);
                double lat2 = Math.toRadians(to[1] / 1_000_000.0);
                double deltaLat = lat2 - lat1;
                double deltaLng = Math.toRadians((to[0] - from[0]) / 1_000_000.0);
                double a = Math.pow(Math.sin(deltaLat / 2), 2)
                        + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLng / 2), 2);
                segmentLengths[i] = EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
                if (segmentLengths[i] <= 0 || from[2] <= 0) {
                    throw new IllegalArgumentException("模拟路线必须包含不同的坐标点，且路段速度必须大于0");
                }
                double bearing = Math.toDegrees(Math.atan2(Math.sin(deltaLng) * Math.cos(lat2),
                        Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLng)));
                directions[i] = Math.floorMod((int) Math.round(bearing), 360);
            }
        }

        synchronized void advance(double seconds) {
            if (!Double.isFinite(seconds) || seconds < 0) {
                throw new IllegalArgumentException("行驶时间必须为非负有限数值");
            }
            while (seconds > 0) {
                double metersPerSecond = DRIVING_ROUTE[segment][2] / 3.6;
                double remainingDistance = segmentLengths[segment] - distanceOnSegment;
                double secondsToEnd = remainingDistance / metersPerSecond;
                if (seconds < secondsToEnd) {
                    double distance = seconds * metersPerSecond;
                    distanceOnSegment += distance;
                    totalDistance += distance;
                    break;
                }
                totalDistance += remainingDistance;
                seconds -= secondsToEnd;
                segment = (segment + 1) % DRIVING_ROUTE.length;
                distanceOnSegment = 0;
            }
        }

        synchronized DrivingPosition position() {
            int[] from = DRIVING_ROUTE[segment];
            int[] to = DRIVING_ROUTE[(segment + 1) % DRIVING_ROUTE.length];
            double fraction = distanceOnSegment / segmentLengths[segment];
            int longitude = (int) Math.round(from[0] + (to[0] - from[0]) * fraction);
            int latitude = (int) Math.round(from[1] + (to[1] - from[1]) * fraction);
            return new DrivingPosition(latitude, longitude, from[2] * 10, directions[segment],
                    (long) (totalDistance / 100));
        }
    }
}