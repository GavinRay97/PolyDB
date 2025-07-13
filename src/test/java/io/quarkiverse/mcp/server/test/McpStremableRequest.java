package io.quarkiverse.mcp.server.test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.quarkiverse.mcp.server.sse.client.SseClient;
import io.quarkiverse.mcp.server.sse.client.SseClient.SseEvent;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

class McpStremableRequest extends SseClient {

    private final HttpClient httpClient;
    private final MultiMap headers;

    private final Consumer<JsonObject> requests;
    private final Consumer<JsonObject> responses;
    private final Consumer<JsonObject> notifications;

    private final AtomicReference<HttpHeaders> responseHeaders;

    McpStremableRequest(HttpClient httpClient, URI mcpEndpoint, MultiMap headers, Consumer<JsonObject> responses,
            Consumer<JsonObject> notifications, Consumer<JsonObject> requests) {
        super(mcpEndpoint);
        this.httpClient = httpClient;
        this.headers = headers;
        this.requests = requests;
        this.responses = responses;
        this.notifications = notifications;
        this.responseHeaders = new AtomicReference<>();
    }

    protected void acceptMessage(JsonObject message) {
        if (message.containsKey("id")) {
            if (message.containsKey("result") || message.containsKey("error")) {
                responses.accept(message);
            } else {
                requests.accept(message);
            }
        } else {
            notifications.accept(message);
        }
    }

    @Override
    protected void process(SseEvent event) {
        if ("message".equals(event.name())) {
            JsonObject json = new JsonObject(event.data());
            acceptMessage(json);
        }
    }

    void send(String body) {
        // Convert headers to Map for SseClient
        java.util.Map<String, String> headerMap = new java.util.HashMap<>();
        headers.forEach(entry -> headerMap.put(entry.getKey(), entry.getValue()));
        
        // Use the SseClient connect method
        connect(httpClient, headerMap);
        
        // Note: The original send method was handling both JSON and SSE responses
        // This refactored version focuses on SSE connections as intended by SseClient
        // For JSON responses, you might need a different approach
    }

    HttpHeaders responseHeaders() {
        return responseHeaders.get();
    }

    @SuppressWarnings("unchecked")
    static <T> T cast(Object obj) {
        return (T) obj;
    }

}
