package com.workshopbridge;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyless Steam Web API access: ISteamRemoteStorage/GetPublishedFileDetails.
 * Used for update checks (comparing remote time_updated against our map).
 * No API key required for this endpoint.
 */
public final class WorkshopApi {
    private static final String DETAILS_URL =
            // system-property hook so tests can point at a local stub server
            System.getProperty("workshopbridge.steamApiUrl",
                    "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private WorkshopApi() {}

    /**
     * Returns workshopId -> time_updated (unix seconds) for the given ids.
     * Ids with no usable entry are absent from the map.
     */
    public static Map<String, Long> getTimeUpdated(List<String> workshopIds) throws IOException {
        Map<String, Long> out = new LinkedHashMap<>();
        if (workshopIds.isEmpty()) {
            return out;
        }
        StringBuilder body = new StringBuilder("itemcount=").append(workshopIds.size());
        for (int i = 0; i < workshopIds.size(); i++) {
            body.append("&publishedfileids%5B").append(i).append("%5D=")
                    .append(URLEncoder.encode(workshopIds.get(i), StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(DETAILS_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "WorkshopBridge/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp;
        try {
            resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (IOException e) {
            throw new IOException(Net.friendlyMessage(e), e);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("Steam API HTTP " + resp.statusCode());
        }
        final Object root;
        try {
            root = Json.parse(resp.body());
        } catch (IllegalArgumentException e) {
            throw new IOException("bad JSON from Steam API", e);
        }
        Map<String, Object> response = Json.object(Json.object(root).get("response"));
        if (response == null) {
            return out;
        }
        List<Object> details = Json.array(response.get("publishedfiledetails"));
        if (details == null) {
            return out;
        }
        for (Object d : details) {
            Map<String, Object> m = Json.object(d);
            if (m == null) {
                continue;
            }
            Object idObj = m.get("publishedfileid");
            final String id;
            if (idObj instanceof Number) {
                id = String.valueOf(((Number) idObj).longValue());
            } else {
                id = idObj == null ? null : String.valueOf(idObj);
            }
            Object tu = m.get("time_updated");
            long timeUpdated = tu instanceof Number ? ((Number) tu).longValue() : 0L;
            if (id != null && !id.isEmpty() && timeUpdated > 0) {
                out.put(id, timeUpdated);
            }
        }
        return out;
    }
}
