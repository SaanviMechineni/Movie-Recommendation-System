package movierec.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import movierec.model.Movie;
import movierec.util.JsonParser;

/**
 * Talks to The Movie Database (TMDB) and converts its JSON into our Movie class.
 * Only the server uses this class - the browser never sees the API key.
 */
public class TmdbClient {
    private static final Map<Integer, String> GENRES = new HashMap<>();
    static {
        GENRES.put(28, "Action");   GENRES.put(12, "Adventure");  GENRES.put(16, "Animation");
        GENRES.put(35, "Comedy");   GENRES.put(80, "Crime");      GENRES.put(99, "Documentary");
        GENRES.put(18, "Drama");    GENRES.put(10751, "Family");  GENRES.put(14, "Fantasy");
        GENRES.put(36, "History");  GENRES.put(27, "Horror");     GENRES.put(10402, "Musical");
        GENRES.put(9648, "Mystery");GENRES.put(10749, "Romance"); GENRES.put(878, "Sci-Fi");
        GENRES.put(10770, "TV Movie"); GENRES.put(53, "Thriller"); GENRES.put(10752, "War");
        GENRES.put(37, "Western");
    }

    private static final int MAX_ATTEMPTS = 6;

    private final ApiConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .sslContext(SystemTrust.context())       // also trust the Windows certificate store (antivirus HTTPS scanning)
            .build();

    public TmdbClient(ApiConfig config) { this.config = config; }

    public String imageBase() { return config.imageUrl(); }

    public static Collection<String> knownGenres() { return new TreeSet<>(GENRES.values()); }

    // ---------------------------------------------------------------- public calls

    /** Title search (first page, up to 20 results). Results have no cast/keywords yet. */
    public List<Movie> search(String query) throws ApiException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("query", query);
        p.put("include_adult", "false");
        p.put("language", "en-US");
        return parseResults(get("/search/movie", p));
    }

    /** Full details: genres, director, cast, keywords. */
    public Movie details(int tmdbId) throws ApiException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("append_to_response", "credits,keywords");
        p.put("language", "en-US");
        Map<String, Object> m = JsonParser.obj(get("/movie/" + tmdbId, p));
        if (m.isEmpty()) throw new ApiException("Movie not found", 404);

        List<String> genres = new ArrayList<>();
        for (Object g : JsonParser.list(m.get("genres"))) genres.add(genreName(JsonParser.str(JsonParser.obj(g).get("name"), "")));
        genres.removeIf(String::isEmpty);

        List<String> keywords = new ArrayList<>();
        for (Object k : JsonParser.list(JsonParser.obj(m.get("keywords")).get("keywords"))) {
            String name = JsonParser.str(JsonParser.obj(k).get("name"), "");
            if (!name.isEmpty() && keywords.size() < 12) keywords.add(name.toLowerCase());
        }

        Map<String, Object> credits = JsonParser.obj(m.get("credits"));
        String director = "";
        for (Object c : JsonParser.list(credits.get("crew"))) {
            if ("Director".equals(JsonParser.str(JsonParser.obj(c).get("job"), ""))) {
                director = JsonParser.str(JsonParser.obj(c).get("name"), "");
                break;
            }
        }
        List<String> cast = new ArrayList<>();
        for (Object c : JsonParser.list(credits.get("cast"))) {
            String name = JsonParser.str(JsonParser.obj(c).get("name"), "");
            if (!name.isEmpty() && cast.size() < 8) cast.add(name);
        }
        return build(m, genres, keywords, director, cast, true);
    }

    /** Finds the poster path of a movie we already know by title and year; "" if TMDB has none. */
    public String findPosterPath(String title, int year) throws ApiException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("query", title);
        p.put("include_adult", "false");
        if (year > 0) p.put("year", String.valueOf(year));
        String fallback = "";
        for (Movie m : parseResults(get("/search/movie", p))) {
            if (m.getPosterPath() == null) continue;
            if (m.getYear() == year && m.getTitle().equalsIgnoreCase(title)) return m.getPosterPath();
            if (fallback.isEmpty()) fallback = m.getPosterPath();
        }
        return fallback;
    }

    // ---------------------------------------------------------------- JSON -> Movie

    private List<Movie> parseResults(Object json) {
        List<Movie> out = new ArrayList<>();
        for (Object item : JsonParser.list(JsonParser.obj(json).get("results"))) {
            Map<String, Object> m = JsonParser.obj(item);
            if (!(m.get("id") instanceof Double)) continue;
            List<String> genres = new ArrayList<>();
            for (Object gid : JsonParser.list(m.get("genre_ids"))) {
                String name = GENRES.get((int) JsonParser.num(gid, -1));
                if (name != null) genres.add(name);
            }
            out.add(build(m, genres, Collections.<String>emptyList(), "", Collections.<String>emptyList(), false));
        }
        return out;
    }

    private Movie build(Map<String, Object> m, List<String> genres, List<String> keywords,
                        String director, List<String> cast, boolean detailed) {
        int tmdbId = (int) JsonParser.num(m.get("id"), 0);
        String date = JsonParser.str(m.get("release_date"), "");
        int year = 0;
        if (date.length() >= 4) {
            try { year = Integer.parseInt(date.substring(0, 4)); } catch (NumberFormatException ignored) { }
        }
        double rating = Math.round(JsonParser.num(m.get("vote_average"), 0) * 10) / 10.0;
        String poster = JsonParser.str(m.get("poster_path"), null);
        return new Movie(Movie.EXTERNAL_OFFSET + tmdbId, JsonParser.str(m.get("title"), "Untitled"), year, rating,
                languageName(JsonParser.str(m.get("original_language"), "")), genres, keywords, director,
                JsonParser.str(m.get("overview"), ""), poster, cast, tmdbId, detailed,
                (int) JsonParser.num(m.get("vote_count"), 0));
    }

    static String genreName(String tmdbName) {
        if ("Science Fiction".equals(tmdbName)) return "Sci-Fi";
        if ("Music".equals(tmdbName)) return "Musical";
        return tmdbName;
    }

    static String languageName(String code) {
        if (code.isEmpty() || code.equals("xx")) return "Unknown";
        if (code.equals("cn")) return "Cantonese";
        String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() ? code : name;
    }

    // ---------------------------------------------------------------- HTTP

    private Object get(String path, Map<String, String> params) throws ApiException {
        StringBuilder url = new StringBuilder(config.baseUrl()).append(path).append('?');
        boolean bearer = config.apiKey().startsWith("eyJ");      // v4 "Read Access Token" is a JWT
        if (!bearer) url.append("api_key=").append(enc(config.apiKey())).append('&');
        for (Map.Entry<String, String> e : params.entrySet()) {
            url.append(enc(e.getKey())).append('=').append(enc(e.getValue())).append('&');
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        if (bearer) req.header("Authorization", "Bearer " + config.apiKey());

        HttpRequest request = req.build();
        HttpResponse<String> res = null;
        IOException lastError = null;
        // Antivirus HTTPS scanners (e.g. Avast) and flaky Wi-Fi reset some connections at random; a fresh attempt usually succeeds.
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && res == null; attempt++) {
            try {
                res = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                lastError = e;
                if (attempt < MAX_ATTEMPTS) {
                    try { Thread.sleep(120L * attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new ApiException("The search was interrupted.", ie); }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException("The search was interrupted.", e);
            }
        }
        if (res == null) {
            throw new ApiException("Could not reach the movie database. Check your internet connection.", lastError);
        }
        int code = res.statusCode();
        if (code == 401 || code == 403) throw new ApiException("The movie API key was rejected. Check MOVIE_API_KEY.", code);
        if (code == 404) throw new ApiException("Movie not found", 404);
        if (code == 429) throw new ApiException("Too many requests to the movie database. Try again in a moment.", code);
        if (code >= 400) throw new ApiException("The movie database returned an error (HTTP " + code + ").", code);
        try {
            return JsonParser.parse(res.body());
        } catch (IllegalArgumentException e) {
            throw new ApiException("The movie database sent an unreadable response.", e);
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
