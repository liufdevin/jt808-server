package org.yzh.web.service;

import org.yzh.web.model.vo.GpsLocation;
import java.util.List;

/** 事务内的最终位置快照，只在事务成功提交后交付订阅者。 */
public record GpsLocationsCommitted(List<GpsLocation> locations) { }
