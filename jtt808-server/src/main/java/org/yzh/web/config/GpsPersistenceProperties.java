package org.yzh.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jt-server.persistence")
public class GpsPersistenceProperties {
    private boolean initializeSchema = true;
    private CoordinateSystem coordinateSystem = CoordinateSystem.UNKNOWN;

    public enum CoordinateSystem {
        UNKNOWN, WGS84, GCJ02, BD09
    }
}
