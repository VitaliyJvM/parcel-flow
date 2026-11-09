package ca.vm.parcelflow.support.security;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Just enough of a browser to run an OIDC login: a cookie jar per origin, no automatic redirects
 * (each hop is asserted), and form submission from real HTML.
 *
 * <p>Cookies are scoped by host <em>and port</em>, which is stricter than a real browser (cookies
 * ignore ports). The application and Keycloak both run on {@code localhost} in this test, so a real
 * browser would send every cookie to both; keeping them apart makes each assertion about which
 * server set what unambiguous.
 */
public final class TestBrowser {

    /** A response plus the raw {@code Set-Cookie} headers it carried. */
    public record Response(int status, String body, URI uri, Map<String, List<String>> headers) {

        public String location() {
            List<String> values = headers.getOrDefault("location", List.of());
            return values.isEmpty() ? null : uri.resolve(values.getFirst()).toString();
        }

        /** The raw Set-Cookie header for a cookie name, or null. */
        public String setCookie(String name) {
            return headers.getOrDefault("set-cookie", List.of()).stream()
                    .filter(header -> header.startsWith(name + "="))
                    .reduce((first, second) -> second)
                    .orElse(null);
        }

        public Document html() {
            return Jsoup.parse(body, uri.toString());
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** origin ("localhost:8080") -> cookie name -> value. */
    private final Map<String, Map<String, String>> jar = new HashMap<>();

    public Response get(String url) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), Map.of());
    }

    public Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), headers);
    }

    public Response postForm(String url, Map<String, String> form, Map<String, String> headers)
            throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("Content-Type", "application/x-www-form-urlencoded");
        return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body)), all);
    }

    public Response postJson(String url, String json, Map<String, String> headers)
            throws IOException, InterruptedException {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("Content-Type", "application/json");
        return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(json)), all);
    }

    /** Submits the Keycloak login form found in {@code loginPage}. */
    public Response submitLogin(Response loginPage, String username, String password)
            throws IOException, InterruptedException {
        Element form = loginPage.html().selectFirst("form#kc-form-login");
        if (form == null) {
            throw new IllegalStateException("No Keycloak login form in:\n" + loginPage.body());
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (Element input : form.select("input[name]")) {
            fields.put(input.attr("name"), input.attr("value"));
        }
        fields.put("username", username);
        fields.put("password", password);
        return postForm(form.absUrl("action"), fields, Map.of());
    }

    public String cookie(String url, String name) {
        return jar.getOrDefault(origin(URI.create(url)), Map.of()).get(name);
    }

    public void setCookie(String url, String name, String value) {
        jar.computeIfAbsent(origin(URI.create(url)), k -> new HashMap<>()).put(name, value);
    }

    private Response send(HttpRequest.Builder builder, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest probe = builder.build();
        URI uri = probe.uri();
        Map<String, String> cookies = jar.getOrDefault(origin(uri), Map.of());
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        headers.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        Map<String, List<String>> lowerCased = new HashMap<>();
        response.headers().map().forEach((key, values) ->
                lowerCased.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).addAll(values));
        for (String header : lowerCased.getOrDefault("set-cookie", List.of())) {
            store(uri, header);
        }
        return new Response(response.statusCode(), response.body(), uri, lowerCased);
    }

    private void store(URI uri, String setCookie) {
        String[] parts = setCookie.split(";");
        String[] nameValue = parts[0].split("=", 2);
        String name = nameValue[0].trim();
        String value = nameValue.length > 1 ? nameValue[1].trim() : "";
        boolean expired = value.isEmpty();
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].trim().toLowerCase(Locale.ROOT);
            if (attribute.equals("max-age=0") || attribute.startsWith("expires=thu, 01 jan 1970")) {
                expired = true;
            }
        }
        Map<String, String> cookies = jar.computeIfAbsent(origin(uri), k -> new HashMap<>());
        if (expired) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }

    private static String origin(URI uri) {
        return uri.getHost() + ":" + uri.getPort();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
