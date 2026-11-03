package movierec;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import movierec.data.MovieRepository;
import movierec.model.Movie;
import movierec.service.*;
import movierec.service.MovieCatalog.SearchResult;

/**
 * Checks the movie search against the REAL configured API (needs MOVIE_API_KEY). Run with live-check.bat.
 * For each title: found? poster really loads (HTTP 200 image)? title / year / rating / overview present?
 * details open (director + cast)? partial and UPPER/lower-case queries find it too?
 */
public class LiveSearchCheck {
    private static int failures = 0;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).sslContext(SystemTrust.context())
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    // query, expected title fragment (normalised), expected year, partial query
    private static final String[][] CASES = {
        {"Home Alone", "home alone", "1990", "home alo"},
        {"Titanic", "titanic", "1997", "TITAN"},
        {"Avatar", "avatar", "2009", "avata"},
        {"Inception", "inception", "2010", "incep"},
        {"RRR", "rrr", "2022", "rr"},
        {"KGF", "k.g.f", "2018", "kg"},
        {"Pushpa", "pushpa", "2021", "pushp"},
        {"Interstellar", "interstellar", "2014", "INTERSTEL"},
        {"The Matrix", "matrix", "1999", "the matri"},
    };

    private static void check(boolean ok, String name) {
        System.out.println("   " + (ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static Movie find(List<Movie> all, String fragment, int year) {
        Movie byTitle = null;
        for (Movie m : all) {
            if (!norm(m.getTitle()).contains(norm(fragment))) continue;
            if (m.getYear() == year) return m;
            if (byTitle == null) byTitle = m;
        }
        return byTitle;
    }

    static List<Movie> everything(SearchResult r) {
        List<Movie> all = new ArrayList<>(r.local);
        all.addAll(r.online);
        return all;
    }

    /** Fetches the image URL and checks it is a real image. */
    static boolean imageLoads(String url) {
        try {
            HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String type = res.headers().firstValue("Content-Type").orElse("");
            return res.statusCode() == 200 && type.startsWith("image/") && res.body().length > 500;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        ApiConfig config = ApiConfig.load();
        if (!config.hasKey()) {
            for (String line : config.report()) System.out.println("  " + line);
            System.out.println(config.setupHint());
            System.exit(2);
        }
        MovieRepository repo;
        try (InputStream in = new java.io.FileInputStream(args.length > 0 ? args[0] : "webapp/WEB-INF/data/movies.txt")) {
            repo = new MovieRepository(in);
        }
        TmdbClient client = new TmdbClient(config);
        MovieCatalog catalog = new MovieCatalog(repo, client);
        System.out.println("Checking against " + config.baseUrl() + "\n");

        for (String[] c : CASES) {
            String query = c[0];
            int year = Integer.parseInt(c[2]);
            System.out.println("== " + query);
            SearchResult r = catalog.search(query, "", "", "rating");
            if (r.apiStatus.equals("error")) {
                check(false, "API error: " + r.apiMessage);
                continue;
            }
            Movie m = find(everything(r), c[1], year);
            check(m != null, "found in results (" + r.local.size() + " library + " + r.online.size() + " online)");
            if (m == null) continue;
            String source = m.isExternal() ? "TMDB" : "library";
            System.out.println("   -> " + m.getTitle() + " (" + m.getYear() + ")  rating " + m.getRating() + "  [" + source + "]");

            check(m.getYear() == year, "year is " + year + " (got " + m.getYear() + ")");
            check(m.getRating() > 0 && m.getRating() <= 10, "rating in range: " + m.getRating());
            check(!m.getDescription().isEmpty(), "overview present: \"" + abbreviate(m.getDescription()) + "\"");

            String posterUrl = catalog.resolvePosterImage(m.getId(), "w342");
            check(posterUrl != null && imageLoads(posterUrl), "poster loads: " + posterUrl);

            Movie full = catalog.getDetailed(m.getId());
            if (m.isExternal()) {
                check(full.isDetailed() && !full.getDirector().isEmpty() && !full.getCast().isEmpty(),
                        "details open: director " + full.getDirector() + ", cast " + full.getCast().subList(0, Math.min(3, full.getCast().size())));
            } else {
                check(full != null, "details open (library movie)");
            }

            for (String variant : new String[] {c[3], query.toUpperCase(Locale.ROOT), query.toLowerCase(Locale.ROOT)}) {
                SearchResult v = catalog.search(variant, "", "", "rating");
                check(find(everything(v), c[1], year) != null, "query \"" + variant + "\" also finds it");
            }
        }
        System.out.println(failures == 0 ? "\nALL LIVE CHECKS PASSED" : "\n" + failures + " LIVE CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String abbreviate(String s) { return s.length() > 70 ? s.substring(0, 70) + "..." : s; }
}
