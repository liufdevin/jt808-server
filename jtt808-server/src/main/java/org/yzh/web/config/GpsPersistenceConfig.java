package org.yzh.web.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

@Slf4j
@Configuration
@EnableConfigurationProperties(GpsPersistenceProperties.class)
public class GpsPersistenceConfig {

    @Bean
    public InitializingBean gpsSchemaInitializer(DataSource dataSource,
                                                GpsPersistenceProperties properties) {
        return () -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.queryForObject("SELECT 1", Integer.class);
            if (properties.isInitializeSchema()) {
                ResourceDatabasePopulator scripts = new ResourceDatabasePopulator(
                        new ClassPathResource("db/mysql-schema.sql"));
                scripts.setSqlScriptEncoding("UTF-8");
                scripts.execute(dataSource);
                // 已部署的旧表保留数据，仅补充订阅排序所需的列；重复启动不重复修改。
                addColumnIfMissing(jdbc, "report_hash", "BINARY(32) NULL");
                addColumnIfMissing(jdbc, "revision", "BIGINT UNSIGNED NOT NULL DEFAULT 0");
            }
            jdbc.queryForList("SELECT revision,report_hash FROM gps_device_location LIMIT 0");
            for (String table : new String[]{"gps_device", "gps_device_location", "gps_device_track"}) {
                jdbc.queryForList("SELECT client_id FROM " + table + " LIMIT 0");
            }
            log.info("MySQL 设备与定位数据存储已就绪，自动建表={}", properties.isInitializeSchema());
        };
    }

    private void addColumnIfMissing(JdbcTemplate jdbc, String column, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='gps_device_location' AND column_name=?",
                Integer.class, column);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE gps_device_location ADD COLUMN " + column + " " + definition);
        }
    }
}
