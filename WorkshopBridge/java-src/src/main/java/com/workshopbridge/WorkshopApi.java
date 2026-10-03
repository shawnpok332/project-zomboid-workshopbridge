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
        if (workshopIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        StringBuilder body = new StringBuilder("itemcount=").append(workshopIds.size());
        for (int i = 0; i < workshopIds.size(); i++) {
            body.append("&publishedfileids%5B").append(i).append("%5D=")
                    .append(URLEncoder.encode(workshopIds.get(i), StandardCharsets.UTF_8));
        }
        final String raw = postForm(DETAILS_URL, body.toString());
        try {
            return parseTimeUpdated(raw);
        } catch (IllegalArgumentException e) {
            // a malformed response must fail the check: silently treating it
            // as "no items listed" would misreport every mod as deleted
            throw new IOException("malformed Steam API response: " + e.getMessage(), e);
        }
    }

    private static String postForm(String url, String formBody) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "WorkshopBridge/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
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
        return resp.body();
    }

    /**
     * Extracts workshopId -> time_updated from a GetPublishedFileDetails
     * response body. Package-private so tests can run it against captured
     * real responses (see tests/java/fixtures/).
     *
     * @throws IllegalArgumentException when the response is not JSON or is
     *         missing the expected structure ({@code response} object with a
     *         {@code publishedfiledetails} array). Individual malformed
     *         entries are skipped; a structurally invalid whole response is
     *         never silently treated as "no items".
     */
    static Map<String, Long> parseTimeUpdated(String json) {
        Map<String, Long> out = new LinkedHashMap<>();
        final Object root = Json.parse(json); // throws on malformed JSON
        Map<String, Object> rootObj = Json.object(root);
        Map<String, Object> response = rootObj == null ? null : Json.object(rootObj.get("response"));
        if (response == null) {
            throw new IllegalArgumentException("missing 'response' object");
        }
        List<Object> details = Json.array(response.get("publishedfiledetails"));
        if (details == null) {
            throw new IllegalArgumentException("missing 'publishedfiledetails' array");
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
