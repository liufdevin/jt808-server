package org.yzh.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableCaching
@EnableScheduling
@SpringBootApplication
public class JT808Application {

    public static void main(String[] args) {
        //Hikari连接池提供的JDBC Connection连接有效性检测,默认为500毫秒
        System.setProperty("com.zaxxer.hikari.aliveBypassWindowMs", "2000");
        ServletWebServerApplicationContext context = (ServletWebServerApplicationContext) SpringApplication.run(JT808Application.class, args);
        String baseUrl = "http://127.0.0.1:" + context.getWebServer().getPort() + context.getServletContext().getContextPath();
        log.info("***Spring 启动成功***");
        log.info("接口文档：{}/doc.html", baseUrl);
        log.info("消息监控：{}/ws.html", baseUrl);
    }
}
