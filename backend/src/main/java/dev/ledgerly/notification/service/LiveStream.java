package dev.ledgerly.notification.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Open server-sent-event streams per user, so a notification reaches every tab the user has open the moment the
 * consumer commits it.
 * Limitation (documented in ADR-012): streams live in this instance's memory. With several instances, the
 * consumer that handles an event may not hold the user's stream; a broadcast topic would fix that.
 */
@Component
public class LiveStream {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();

    public SseEmitter open(UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        streams.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> remove(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        send(userId, emitter, SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        return emitter;
    }

    public void push(UUID userId, String eventName, Object data) {
        for (SseEmitter emitter : streams.getOrDefault(userId, Set.of())) {
            send(userId, emitter, SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        }
    }

    public int openStreams() {
        return streams.values().stream().mapToInt(Set::size).sum();
    }

    /** Proxies and load balancers drop idle connections: a comment every 20 seconds keeps the stream open. */
    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        streams.forEach((userId, emitters) -> emitters.forEach(e -> send(userId, e, SseEmitter.event().comment("keep-alive"))));
    }

    private void send(UUID userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // The browser went away: forget the stream, never fail the caller.
            remove(userId, emitter);
        }
    }

    private void remove(UUID userId, SseEmitter emitter) {
        streams.computeIfPresent(userId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
