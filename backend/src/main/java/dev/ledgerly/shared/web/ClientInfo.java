package dev.ledgerly.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Caller network details for the current request (null fields outside a web request). */
public record ClientInfo(String ip, String userAgent) {

    private static final int MAX_USER_AGENT = 255;

    public static ClientInfo current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String agent = request.getHeader("User-Agent");
            if (agent != null && agent.length() > MAX_USER_AGENT) {
                agent = agent.substring(0, MAX_USER_AGENT);
            }
            return new ClientInfo(request.getRemoteAddr(), agent);
        }
        return new ClientInfo(null, null);
    }
}
