package movierec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * A tiny stand-in for the TMDB API (same URLs, same JSON shape) used by the tests, so they
 * run offline and without an API key.   Start standalone:  java movierec.FakeTmdbServer 9099
 * Then run the website with:  MOVIE_API_KEY=test-key  MOVIE_API_BASE_URL=http://localhost:9099/3
 *                             MOVIE_IMAGE_BASE_URL=http://localhost:9099/t/p/
 */
public class FakeTmdbServer {
    public static final String KEY = "test-key";

    private static class Film {
        final int id; final String title, date, lang, genres, overview, poster, director;
        final double vote; final String[] cast, keywords;
        Film(int id, String title, String date, String lang, String genres, double vote, String overview,
             String poster, String director, String cast, String keywords) {
            this.id = id; this.title = title; this.date = date; this.lang = lang; this.genres = genres;
            this.vote = vote; this.overview = overview; this.poster = poster; this.director = director;
            this.cast = cast.split(","); this.keywords = keywords.split(",");
        }
    }

    private static final Map<Integer, String> GENRE_NAMES = new HashMap<>();
    static {
        GENRE_NAMES.put(35, "Comedy"); GENRE_NAMES.put(10751, "Family"); GENRE_NAMES.put(18, "Drama");
        GENRE_NAMES.put(10749, "Romance"); GENRE_NAMES.put(28, "Action"); GENRE_NAMES.put(12, "Adventure");
        GENRE_NAMES.put(14, "Fantasy"); GENRE_NAMES.put(878, "Science Fiction"); GENRE_NAMES.put(80, "Crime"); GENRE_NAMES.put(53, "Thriller");
    }

    private static final List<Film> FILMS = Arrays.asList(
        new Film(771, "Home Alone", "1990-11-16", "en", "35,10751", 7.4, "Eight-year-old Kevin McCallister is accidentally left behind when his family flies to Paris for Christmas.", "/homealone.jpg", "Chris Columbus", "Macaulay Culkin,Joe Pesci,Daniel Stern,Catherine O'Hara", "christmas,burglar,booby trap,home invasion"),
        new Film(772, "Home Alone 2: Lost in New York", "1992-11-20", "en", "35,10751", 6.6, "Kevin boards the wrong plane and ends up alone in New York City.", "/homealone2.jpg", "Chris Columbus", "Macaulay Culkin,Joe Pesci", "christmas,new york,burglar"),
        new Film(597, "Titanic", "1997-11-18", "en", "18,10749", 7.9, "A young aristocrat and a poor artist fall in love aboard the doomed liner.", "/titanic.jpg", "James Cameron", "Leonardo DiCaprio,Kate Winslet", "ship,disaster,love"),
        new Film(19995, "Avatar", "2009-12-15", "en", "28,12,14,878", 7.6, "A paraplegic marine is dispatched to the moon Pandora.", "/avatar.jpg", "James Cameron", "Sam Worthington,Zoe Saldana,Sigourney Weaver", "alien,future,war"),
        new Film(76600, "Avatar: The Way of Water", "2022-12-14", "en", "878,12,28", 7.6, "Jake Sully lives with his new family on Pandora.", "/avatar2.jpg", "James Cameron", "Sam Worthington,Zoe Saldana", "alien,family,ocean"),
        new Film(579974, "RRR", "2022-03-24", "te", "28,18", 7.8, "A fictional story about two legendary revolutionaries.", "/rrr.jpg", "S. S. Rajamouli", "N. T. Rama Rao Jr.,Ram Charan", "freedom fighters,epic"),
        new Film(671, "Harry Potter and the Philosopher's Stone", "2001-11-16", "en", "12,14", 7.9, "A boy discovers he is a wizard.", "/hp1.jpg", "Chris Columbus", "Daniel Radcliffe,Emma Watson,Rupert Grint", "magic,wizard,school"),
        new Film(672, "Harry Potter and the Chamber of Secrets", "2002-11-13", "en", "12,14", 7.7, "Harry returns to Hogwarts for a second year.", "/hp2.jpg", "Chris Columbus", "Daniel Radcliffe,Emma Watson", "magic,wizard,school"),
        new Film(278, "The Shawshank Redemption", "1994-09-23", "en", "18,80", 8.7, "Two imprisoned men bond over a number of years.", "/shawshank.jpg", "Frank Darabont", "Tim Robbins,Morgan Freeman", "prison,friendship"),
        new Film(27205, "Inception", "2010-07-15", "en", "28,878,12", 8.4, "A thief who steals secrets through dreams.", "/inception.jpg", "Christopher Nolan", "Leonardo DiCaprio", "dream,heist"),
        new Film(157336, "Interstellar", "2014-11-05", "en", "12,18,878", 8.4, "The adventures of a group of explorers who make use of a newly discovered wormhole.", "/interstellar.jpg", "Christopher Nolan", "Matthew McConaughey,Anne Hathaway", "space,wormhole"),
        new Film(603, "The Matrix", "1999-03-30", "en", "28,878", 8.2, "A computer hacker learns the true nature of his reality.", "/matrix.jpg", "Lana Wachowski", "Keanu Reeves,Laurence Fishburne", "simulation,hacker"),
        new Film(539517, "K.G.F: Chapter 1", "2018-12-20", "kn", "28,18,80", 7.4, "A young man rises in the Mumbai underworld in the 1970s.", "/kgf.jpg", "Prashanth Neel", "Yash,Srinidhi Shetty", "gold,mafia"),
        new Film(692816, "Pushpa: The Rise - Part 1", "2021-12-17", "te", "28,18,80", 7.0, "A labourer rises through the red sandalwood smuggling syndicate.", "/pushpa.jpg", "Sukumar", "Allu Arjun,Rashmika Mandanna", "smuggling,sandalwood"),
        new Film(999001, "Obscure Film Without Poster", "2015-01-01", "fr", "18", 5.5, "", null, "", "", "")
    );

    private final HttpServer server;
    public final AtomicInteger searchCalls = new AtomicInteger();
    public final AtomicInteger detailCalls = new AtomicInteger();
    public final AtomicInteger imageCalls = new AtomicInteger();

    public FakeTmdbServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/3/search/movie", this::search);
        server.createContext("/3/movie/", this::details);
        server.createContext("/t/p/", this::image);
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }
    public int port() { return server.getAddress().getPort(); }
    public String baseUrl() { return "http://127.0.0.1:" + port() + "/3"; }
    public String imageUrl() { return "http://127.0.0.1:" + port() + "/t/p/"; }

    // ---------------------------------------------------------------- handlers

    private boolean authorised(HttpExchange ex, Map<String, String> q) throws IOException {
        if (KEY.equals(q.get("api_key"))) return true;
        send(ex, 401, "application/json", "{\"status_code\":7,\"status_message\":\"Invalid API key\",\"success\":false}");
        return false;
    }

    private void search(HttpExchange ex) throws IOException {
        Map<String, String> q = params(ex);
        if (!authorised(ex, q)) return;
        searchCalls.incrementAndGet();
        String needle = q.getOrDefault("query", "").toLowerCase();
        String year = q.get("year");
        StringBuilder sb = new StringBuilder("{\"page\":1,\"results\":[");
        boolean first = true;
        for (Film f : FILMS) {
            if (!f.title.toLowerCase().contains(needle)) continue;
            if (year != null && !f.date.startsWith(year)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(f.id)
              .append(",\"title\":").append(q(f.title))
              .append(",\"release_date\":").append(q(f.date))
              .append(",\"original_language\":").append(q(f.lang))
              .append(",\"genre_ids\":[").append(f.genres).append("]")
              .append(",\"vote_average\":").append(f.vote)
              .append(",\"overview\":").append(q(f.overview))
              .append(",\"poster_path\":").append(f.poster == null ? "null" : q(f.poster))
              .append("}");
        }
        sb.append("],\"total_results\":0}");
        send(ex, 200, "application/json", sb.toString());
    }

    private void details(HttpExchange ex) throws IOException {
        Map<String, String> q = params(ex);
        if (!authorised(ex, q)) return;
        detailCalls.incrementAndGet();
        String idText = ex.getRequestURI().getPath().substring("/3/movie/".length());
        for (Film f : FILMS) {
            if (!String.valueOf(f.id).equals(idText)) continue;
            StringBuilder genres = new StringBuilder();
            for (String g : f.genres.split(",")) {
                if (genres.length() > 0) genres.append(',');
                genres.append("{\"id\":").append(g).append(",\"name\":").append(q(GENRE_NAMES.get(Integer.parseInt(g)))).append("}");
            }
            StringBuilder cast = new StringBuilder(), kw = new StringBuilder();
            for (String c : f.cast) if (!c.isEmpty()) { if (cast.length() > 0) cast.append(','); cast.append("{\"name\":").append(q(c)).append("}"); }
            for (String k : f.keywords) if (!k.isEmpty()) { if (kw.length() > 0) kw.append(','); kw.append("{\"name\":").append(q(k)).append("}"); }
            String crew = f.director.isEmpty() ? "" : "{\"name\":" + q(f.director) + ",\"job\":\"Director\"}";
            send(ex, 200, "application/json", "{\"id\":" + f.id + ",\"title\":" + q(f.title)
                + ",\"release_date\":" + q(f.date) + ",\"original_language\":" + q(f.lang)
                + ",\"vote_average\":" + f.vote + ",\"overview\":" + q(f.overview)
                + ",\"poster_path\":" + (f.poster == null ? "null" : q(f.poster))
                + ",\"genres\":[" + genres + "],\"credits\":{\"cast\":[" + cast + "],\"crew\":[" + crew + "]}"
                + ",\"keywords\":{\"keywords\":[" + kw + "]}}");
            return;
        }
        send(ex, 404, "application/json", "{\"success\":false,\"status_code\":34}");
    }

    /** Serves a coloured 2:3 PNG with the file name written on it (stands in for the poster CDN). */
    private void image(HttpExchange ex) throws IOException {
        imageCalls.incrementAndGet();
        String name = ex.getRequestURI().getPath();
        int hue = Math.abs(name.hashCode()) % 360;
        BufferedImage img = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.getHSBColor(hue / 360f, 0.55f, 0.55f));
        g.fillRect(0, 0, 200, 300);
        g.setColor(Color.WHITE);
        g.drawString(name.substring(name.lastIndexOf('/') + 1), 20, 150);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        send(ex, 200, "image/png", out.toByteArray());
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, String> params(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String q(String s) { return movierec.util.Json.str(s); }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        send(ex, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9099;
        FakeTmdbServer s = new FakeTmdbServer(port);
        s.start();
        System.out.println("Fake TMDB running on " + s.baseUrl() + "  (key: " + KEY + ")");
    }
}
