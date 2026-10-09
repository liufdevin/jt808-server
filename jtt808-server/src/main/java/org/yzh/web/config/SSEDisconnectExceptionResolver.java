package org.yzh.web.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.handler.AbstractHandlerExceptionResolver;
import org.springframework.web.util.DisconnectedClientHelper;

import java.io.IOException;
import java.util.Locale;

/** 正常 SSE 客户端断开时，不再向已关闭的响应写入错误内容。 */
@Slf4j
@Component
public class SSEDisconnectExceptionResolver extends AbstractHandlerExceptionResolver {

    public SSEDisconnectExceptionResolver() {
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    protected ModelAndView doResolveException(HttpServletRequest request,
                                             HttpServletResponse response,
                                             Object handler,
                                             Exception exception) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!(path.equals("/sse/connect") || path.equals("/api/gps/locations/subscribe"))
                || !isClientDisconnect(exception)) {
            return null;
        }

        log.debug("SSE 客户端已断开：{}", exception.getMessage());
        // 空 ModelAndView 表示已处理；不设置状态、不渲染、不再次写入响应。
        return new ModelAndView();
    }

    private boolean isClientDisconnect(Exception exception) {
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            return true;
        }
        // JDK 的底层 Socket 错误可能使用本地语言，补充中文断开消息。
        if (!(exception instanceof IOException || exception instanceof ServletException)) {
            return false;
        }
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        if (!(cause instanceof IOException) || cause.getMessage() == null) {
            return false;
        }
        String message = cause.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("connection reset")
                || message.contains("断开的管道")
                || message.contains("连接被对方重设")
                || message.contains("连接被对方重置")
                || message.contains("远程主机强迫关闭了一个现有的连接");
    }
}
