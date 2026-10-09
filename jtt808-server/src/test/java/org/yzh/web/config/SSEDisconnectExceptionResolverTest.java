package org.yzh.web.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SSEDisconnectExceptionResolverTest {

    private final SSEDisconnectExceptionResolver resolver = new SSEDisconnectExceptionResolver();

    @Test
    void handlesKnownDisconnectsWithoutWritingToTheClosedResponse() {
        for (String message : new String[]{"Broken pipe", "Connection reset by peer",
                "Connection reset", "断开的管道", "连接被对方重设", "连接被对方重置"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            ModelAndView result = resolver.resolveException(request("/sse/connect"), response,
                    null, new IOException(message));
            assertNotNull(result, message);
            assertTrue(result.isEmpty(), message);
            assertEquals(200, response.getStatus(), message);
            assertFalse(response.isCommitted(), message);
            assertEquals(0, response.getContentAsByteArray().length, message);
        }
    }

    @Test
    void handlesWrappedAndAsyncDisconnectsWithAContextPath() {
        MockHttpServletRequest request = request("/jt808/sse/connect");
        request.setContextPath("/jt808");
        assertNotNull(resolver.resolveException(request, new MockHttpServletResponse(), null,
                new ServletException(new IOException("连接被对方重设"))));
        assertNotNull(resolver.resolveException(request, new MockHttpServletResponse(), null,
                new AsyncRequestNotUsableException("Response no longer usable")));
    }

    @Test
    void handlesAppSubscriptionDisconnectWithoutSwallowingOtherAppErrors() {
        assertNotNull(resolver.resolveException(request("/api/gps/locations/subscribe"), new MockHttpServletResponse(),
                null, new IOException("Connection reset")));
        assertNull(resolver.resolveException(request("/api/gps/devices"), new MockHttpServletResponse(),
                null, new IOException("Connection reset")));
    }

    @Test
    void preservesUnrelatedFailuresAndOtherEndpoints() {
        assertNull(resolver.resolveException(request("/sse/connect"), new MockHttpServletResponse(),
                null, new IOException("Disk I/O failed")));
        assertNull(resolver.resolveException(request("/sse/connect"), new MockHttpServletResponse(),
                null, new IllegalStateException("Unexpected processing failure")));
        assertNull(resolver.resolveException(request("/sse/connect"), new MockHttpServletResponse(),
                null, new ResourceAccessException("Upstream failed", new IOException("Broken pipe"))));
        assertNull(resolver.resolveException(request("/device/8201"), new MockHttpServletResponse(),
                null, new IOException("Broken pipe")));
    }

    private MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
