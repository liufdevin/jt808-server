-- 仅创建缺少的表，不删除或清空已有数据。
CREATE TABLE IF NOT EXISTS gps_device (
    client_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'JT808终端手机号/连接标识，保留前导零',
    device_id VARCHAR(128) NULL,
    plate_no VARCHAR(128) NULL,
    protocol_version INT NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS gps_device_location (
    client_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_time DATETIME NOT NULL,
    received_time DATETIME(3) NOT NULL,
    latitude_raw INT UNSIGNED NOT NULL,
    longitude_raw INT UNSIGNED NOT NULL,
    latitude DECIMAL(8,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL,
    altitude INT UNSIGNED NOT NULL,
    speed DECIMAL(6,1) NOT NULL COMMENT 'km/h',
    direction SMALLINT UNSIGNED NOT NULL,
    warn_bit INT UNSIGNED NOT NULL,
    status_bit INT UNSIGNED NOT NULL,
    positioned BOOLEAN NOT NULL,
    coordinate_system VARCHAR(16) NOT NULL,
    attributes_json JSON NOT NULL,
    protocol_version INT NOT NULL,
    message_id SMALLINT UNSIGNED NOT NULL,
    serial_no SMALLINT UNSIGNED NOT NULL,
    report_hash BINARY(32) NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (client_id),
    CONSTRAINT fk_gps_location_device FOREIGN KEY (client_id) REFERENCES gps_device(client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS gps_device_track (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    client_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_time DATETIME NOT NULL,
    received_time DATETIME(3) NOT NULL,
    latitude_raw INT UNSIGNED NOT NULL,
    longitude_raw INT UNSIGNED NOT NULL,
    latitude DECIMAL(8,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL,
    altitude INT UNSIGNED NOT NULL,
    speed DECIMAL(6,1) NOT NULL COMMENT 'km/h',
    direction SMALLINT UNSIGNED NOT NULL,
    warn_bit INT UNSIGNED NOT NULL,
    status_bit INT UNSIGNED NOT NULL,
    positioned BOOLEAN NOT NULL,
    coordinate_system VARCHAR(16) NOT NULL,
    attributes_json JSON NOT NULL,
    protocol_version INT NOT NULL,
    message_id SMALLINT UNSIGNED NOT NULL,
    serial_no SMALLINT UNSIGNED NOT NULL,
    report_hash BINARY(32) NOT NULL COMMENT '定位内容SHA256，用于报文重传去重',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gps_track_report (client_id, report_hash),
    KEY idx_gps_track_client_time (client_id, device_time, id),
    CONSTRAINT fk_gps_track_device FOREIGN KEY (client_id) REFERENCES gps_device(client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
