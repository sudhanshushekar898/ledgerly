package dev.ledgerly.notification.api;

import dev.ledgerly.identity.CurrentUser;
import dev.ledgerly.notification.service.LiveStream;
import dev.ledgerly.notification.service.NotificationService;
import dev.ledgerly.notification.service.NotificationService.Inbox;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;
    private final LiveStream live;
    private final CurrentUser currentUser;

    NotificationController(NotificationService notifications, LiveStream live, CurrentUser currentUser) {
        this.notifications = notifications;
        this.live = live;
        this.currentUser = currentUser;
    }

    @GetMapping
    Inbox inbox() {
        return notifications.inbox(currentUser.id());
    }

    @PostMapping("/read-all")
    ResponseEntity<Void> readAll() {
        notifications.markAllRead(currentUser.id());
        return ResponseEntity.noContent().build();
    }

    /**
     * Server-sent events. The browser reads this with fetch() so it can send the bearer token in a header:
     * EventSource cannot, and a token in the URL would end up in logs. X-Accel-Buffering stops Nginx buffering.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(live.open(currentUser.id()));
    }
}
