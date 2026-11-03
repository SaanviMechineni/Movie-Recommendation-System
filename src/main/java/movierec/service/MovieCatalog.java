package movierec.service;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import movierec.data.MovieRepository;
import movierec.model.Movie;

/**
 * One place to look up any movie: the local library (movies.txt) first, then the online database.
 * Also owns the in-memory caches so the same search / poster / movie is never requested twice.
 */
public class MovieCatalog {
    private static final long SEARCH_TTL_MS = 30 * 60 * 1000L;
    private static final int MAX_CACHED_SEARCHES = 500;

    private final MovieRepository repo;
    private final TmdbClient tmdb;                       // null when no API key is configured

    private final Map<Integer, Movie> online = new ConcurrentHashMap<>();       // movies seen from the API
    private final Map<String, CachedSearch> searches = new ConcurrentHashMap<>();
    private final Map<Integer, String> localPosters = new ConcurrentHashMap<>(); // local id -> poster path ("" = none)
    private final Semaphore posterLimiter = new Semaphore(6);                    // be polite to the API

    private static class CachedSearch {
        final long time = System.currentTimeMillis();
        final List<Movie> movies;
        CachedSearch(List<Movie> movies) { this.movies = movies; }
    }

    /** Result of one search: library matches first, then online matches. */
    public static class SearchResult {
        public final List<Movie> local;
        public final List<Movie> online;
        public final String apiStatus;     // "ok", "idle", "disabled" or "error"
        public final String apiMessage;
        SearchResult(List<Movie> local, List<Movie> online, String apiStatus, String apiMessage) {
            this.local = local; this.online = online; this.apiStatus = apiStatus; this.apiMessage = apiMessage;
        }
    }

    private final String disabledMessage;

    public MovieCatalog(MovieRepository repo, TmdbClient tmdb) {
        this(repo, tmdb, "Online search is off. Add your MOVIE_API_KEY (see README) to search every movie.");
    }

    /** disabledMessage is shown to the user when there is no API key. */
    public MovieCatalog(MovieRepository repo, TmdbClient tmdb, String disabledMessage) {
        this.repo = repo;
        this.tmdb = tmdb;
        this.disabledMessage = disabledMessage;
    }

    public MovieRepository library() { return repo; }
    public boolean apiEnabled() { return tmdb != null; }

    // ------------------------------------------------------------------ search

    public SearchResult search(String q, String genre, String language, String sort) {
        List<Movie> local = new ArrayList<>(repo.search(q, genre, language));
        if ("year".equals(sort)) local.sort(Comparator.comparingInt(Movie::getYear).reversed());
        else if ("title".equals(sort)) local.sort(Comparator.comparing(Movie::getTitle));
        else local.sort(Comparator.comparingDouble(Movie::getRating).reversed());

        String query = q == null ? "" : q.trim();
        if (query.length() < 2) return new SearchResult(local, new ArrayList<Movie>(), "idle", "");
        if (tmdb == null) {
            return new SearchResult(local, new ArrayList<Movie>(), "disabled", disabledMessage);
        }
        try {
            Set<String> have = new HashSet<>();
            for (Movie m : repo.all()) have.add(dedupeKey(m));
            List<Movie> extra = new ArrayList<>();
            for (Movie m : apiSearch(query)) {
                if (have.contains(dedupeKey(m))) continue;
                if (!isBlank(genre) && !m.getGenres().contains(genre)) continue;
                if (!isBlank(language) && !m.getLanguage().equals(language)) continue;
                extra.add(m);
            }
            return new SearchResult(local, extra, "ok", "");
        } catch (ApiException e) {
            return new SearchResult(local, new ArrayList<Movie>(), "error", e.getMessage());
        }
    }

    private List<Movie> apiSearch(String query) throws ApiException {
        String key = query.toLowerCase(Locale.ROOT);
        CachedSearch hit = searches.get(key);
        if (hit != null && System.currentTimeMillis() - hit.time < SEARCH_TTL_MS) return hit.movies;

        List<Movie> found = tmdb.search(query);
        if (found.isEmpty() && query.matches("[A-Za-z]{2,6}")) {
            // acronym titles are often stored with dots: "KGF" -> "K.G.F"
            found = tmdb.search(dotted(query));
        }
        List<Movie> canonical = new ArrayList<>();
        for (Movie m : rankByTitle(query, found)) {
            // keep the already-detailed copy if we have one
            Movie merged = online.merge(m.getId(), m, (old, fresh) -> old.isDetailed() ? old : fresh);
            canonical.add(merged);
        }
        if (searches.size() >= MAX_CACHED_SEARCHES) searches.clear();
        searches.put(key, new CachedSearch(canonical));
        return canonical;
    }

    /**
     * Puts exact title matches first, then titles that start with the query, then the rest.
     * Within each group TMDB's own order (popularity) is kept.
     */
    public static List<Movie> rankByTitle(String query, List<Movie> movies) {
        String literal = query.trim();
        String q = titleKey(query);
        // groups: 0 = identical title, 1 = same letters ignoring punctuation ("Home, Alone"), 2 = starts with query, 3 = rest
        List<List<Movie>> complete = new ArrayList<>(), bare = new ArrayList<>();
        for (int i = 0; i < 4; i++) { complete.add(new ArrayList<Movie>()); bare.add(new ArrayList<Movie>()); }
        for (Movie m : movies) {
            String t = titleKey(m.getTitle());
            int group = m.getTitle().trim().equalsIgnoreCase(literal) ? 0
                    : t.equals(q) ? 1
                    : (!q.isEmpty() && t.startsWith(q)) ? 2 : 3;
            // many TMDB entries are obscure records with no poster or no year - show real films first within a group
            boolean isComplete = m.getPosterPath() != null && m.getYear() > 0;
            (isComplete ? complete : bare).get(group).add(m);
        }
        // inside a group, the film with the most TMDB votes is almost always the one people mean
        Comparator<Movie> byVotes = Comparator.comparingInt(Movie::getVoteCount).reversed();     // stable sort keeps TMDB order on ties
        List<Movie> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            complete.get(i).sort(byVotes);
            bare.get(i).sort(byVotes);
            out.addAll(complete.get(i));
            out.addAll(bare.get(i));
        }
        return out;
    }

    private static String titleKey(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** "kgf" -> "K.G.F" */
    static String dotted(String letters) {
        StringBuilder sb = new StringBuilder();
        for (char c : letters.toUpperCase(Locale.ROOT).toCharArray()) sb.append(c).append('.');
        return sb.substring(0, sb.length() - 1);
    }

    // ------------------------------------------------------------------ lookup

    /** Any movie by id: library, cache, or (for unknown online ids) the API. Null if not found. */
    public Movie get(int id) {
        Movie m = repo.get(id);
        if (m != null) return m;
        m = online.get(id);
        if (m != null) return m;
        if (id > Movie.EXTERNAL_OFFSET && tmdb != null) {
            try {
                Movie fetched = tmdb.details(id - Movie.EXTERNAL_OFFSET);
                online.put(id, fetched);
                return fetched;
            } catch (ApiException e) {
                return null;
            }
        }
        return null;
    }

    /** Like get(), but loads cast / director / keywords for online movies. */
    public Movie getDetailed(int id) {
        Movie m = get(id);
        if (m != null && m.isExternal() && !m.isDetailed() && tmdb != null) {
            try {
                Movie full = tmdb.details(m.getTmdbId());
                online.put(id, full);
                return full;
            } catch (ApiException e) {
                return m;      // still show the basic info we have
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ posters

    /** URL the browser can use for a poster image (relative path or full URL), or null if none is available. */
    public String posterUrl(Movie m, String size) {
        if (m.isExternal()) {
            return m.getPosterPath() == null ? null : tmdb == null ? null : tmdb.imageBase() + size + m.getPosterPath();
        }
        if (m.getPosterPath() != null) return m.getPosterPath();     // poster file saved in webapp/images/posters (works without a key)
        return tmdb == null ? null : "api/poster?id=" + m.getId() + "&size=" + size;
    }

    /** Real image URL for the poster servlet to redirect to (looks the poster up and caches it). */
    public String resolvePosterImage(int id, String size) {
        Movie m = get(id);
        if (m == null || tmdb == null) return null;
        String path = m.isExternal() ? m.getPosterPath() : localPosterPath(m);
        return path == null || path.isEmpty() ? null : tmdb.imageBase() + size + path;
    }

    private String localPosterPath(Movie m) {
        String cached = localPosters.get(m.getId());
        if (cached != null) return cached;
        try {
            posterLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            String path = tmdb.findPosterPath(m.getTitle(), m.getYear());
            localPosters.put(m.getId(), path);
            return path;
        } catch (ApiException e) {
            return null;       // not cached, so we try again next time
        } finally {
            posterLimiter.release();
        }
    }

    // ------------------------------------------------------------------ misc

    public SortedSet<String> genres() {
        SortedSet<String> s = new TreeSet<>(repo.genres());
        if (tmdb != null) s.addAll(TmdbClient.knownGenres());
        return s;
    }

    public SortedSet<String> languages() { return repo.languages(); }

    /** "Amélie" and "Amelie" count as the same title; used to avoid showing a movie twice. */
    static String dedupeKey(Movie m) {
        String t = Normalizer.normalize(m.getTitle(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + "|" + m.getYear();
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
