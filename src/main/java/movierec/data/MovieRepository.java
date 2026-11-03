package movierec.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import movierec.model.Movie;

/** Loads all movies from the pipe-separated dataset file once and keeps them in memory. */
public class MovieRepository {
    private final List<Movie> movies = new ArrayList<>();
    private final Map<Integer, Movie> byId = new HashMap<>();

    /** Format: id|title|year|rating|language|genres(;)|keywords(;)|director|description[|poster path] */
    public MovieRepository(InputStream in) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\|", -1);
                if (p.length < 9) throw new IOException("Bad dataset line: " + line);
                String poster = p.length > 9 && !p[9].trim().isEmpty() ? p[9].trim() : null;   // e.g. images/posters/1.jpg
                Movie m = new Movie(Integer.parseInt(p[0]), p[1], Integer.parseInt(p[2]),
                        Double.parseDouble(p[3]), p[4], split(p[5]), split(p[6]), p[7], p[8],
                        poster, Collections.<String>emptyList(), 0, false);
                movies.add(m);
                byId.put(m.getId(), m);
            }
        }
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split(";")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    public List<Movie> all() { return Collections.unmodifiableList(movies); }
    public Movie get(int id) { return byId.get(id); }

    public SortedSet<String> genres() {
        SortedSet<String> s = new TreeSet<>();
        for (Movie m : movies) s.addAll(m.getGenres());
        return s;
    }

    public SortedSet<String> languages() {
        SortedSet<String> s = new TreeSet<>();
        for (Movie m : movies) s.add(m.getLanguage());
        return s;
    }

    /** Search by title/director/keyword/genre text + optional genre and language filters. */
    public List<Movie> search(String q, String genre, String language) {
        String query = q == null ? "" : q.trim().toLowerCase();
        List<Movie> out = new ArrayList<>();
        for (Movie m : movies) {
            if (!isBlank(genre) && !m.getGenres().contains(genre)) continue;
            if (!isBlank(language) && !m.getLanguage().equals(language)) continue;
            if (!query.isEmpty() && !matches(m, query)) continue;
            out.add(m);
        }
        return out;
    }

    private static boolean matches(Movie m, String q) {
        if (m.getTitle().toLowerCase().contains(q)) return true;
        if (m.getDirector().toLowerCase().contains(q)) return true;
        for (String k : m.getKeywords()) if (k.toLowerCase().contains(q)) return true;
        for (String g : m.getGenres()) if (g.toLowerCase().contains(q)) return true;
        return false;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
