package movierec;

import java.io.FileInputStream;
import java.util.List;
import java.util.Map;
import movierec.data.MovieRepository;
import movierec.model.Movie;
import movierec.service.*;
import movierec.service.MovieCatalog.SearchResult;
import movierec.service.RecommendationService.Result;
import movierec.util.JsonParser;

/** Tests the online-search pipeline against FakeTmdbServer (no internet or real API key needed). */
public class ApiSearchTest {
    private static int failures = 0;

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    private static boolean has(List<Movie> list, String title) {
        for (Movie m : list) if (m.getTitle().equals(title)) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "webapp/WEB-INF/data/movies.txt";
        String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        MovieRepository withPosters = new MovieRepository(new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // the TMDB poster-lookup tests need library movies WITHOUT a saved poster file, so drop that column
        StringBuilder stripped = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            if (!line.isEmpty() && !line.startsWith("#")) line = String.join("|", java.util.Arrays.copyOf(line.split("\\|", -1), 9));
            stripped.append(line).append("\n");
        }
        MovieRepository repo = new MovieRepository(new java.io.ByteArrayInputStream(stripped.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        FakeTmdbServer fake = new FakeTmdbServer(0);
        fake.start();
        try {
            run(repo, withPosters, fake);
        } finally {
            fake.stop();
        }
        System.out.println(failures == 0 ? "\nALL API TESTS PASSED" : "\n" + failures + " API TEST(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(MovieRepository repo, MovieRepository withPosters, FakeTmdbServer fake) throws Exception {
        ApiConfig good = new ApiConfig(FakeTmdbServer.KEY, fake.baseUrl(), fake.imageUrl());
        MovieCatalog catalog = new MovieCatalog(repo, new TmdbClient(good));

        // --- JSON parser ---
        Map<String, Object> parsed = JsonParser.obj(JsonParser.parse(
                "{\"a\":[1,2.5,{\"b\":\"x\\ny\\u00e9\"}],\"t\":true,\"n\":null,\"neg\":-3e2}"));
        check(JsonParser.list(parsed.get("a")).size() == 3, "JsonParser reads arrays");
        check("x\nyé".equals(JsonParser.str(JsonParser.obj(JsonParser.list(parsed.get("a")).get(2)).get("b"), "")), "JsonParser reads escapes");
        check(JsonParser.num(parsed.get("neg"), 0) == -300.0 && Boolean.TRUE.equals(parsed.get("t")) && parsed.get("n") == null,
                "JsonParser reads numbers, booleans, null");
        boolean threw = false;
        try { JsonParser.parse("{\"a\":"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "JsonParser rejects broken JSON");

        // --- searches that must work ---
        SearchResult home = catalog.search("Home Alone", "", "", "rating");
        check(home.local.isEmpty() && home.online.size() == 2 && has(home.online, "Home Alone"),
                "'Home Alone' is not in the library but is found online (" + home.online.size() + " results)");
        check(home.apiStatus.equals("ok"), "api status ok");
        Movie homeAlone = home.online.get(0);
        check(homeAlone.getYear() == 1990 && homeAlone.getRating() == 7.4 && homeAlone.getLanguage().equals("English")
                        && homeAlone.getGenres().contains("Comedy") && homeAlone.getGenres().contains("Family"),
                "online movie converted: year, rating, language, genres");
        check(homeAlone.getId() == Movie.EXTERNAL_OFFSET + 771 && homeAlone.isExternal(), "online id = offset + tmdb id");
        String poster = catalog.posterUrl(homeAlone, "w342");
        check(poster != null && poster.equals(fake.imageUrl() + "w342/homealone.jpg"), "online movie has real poster url: " + poster);

        SearchResult titanic = catalog.search("Titanic", "", "", "rating");
        check(titanic.local.size() == 1 && titanic.online.isEmpty(), "'Titanic': library copy shown, online duplicate hidden");
        SearchResult avatar = catalog.search("Avatar", "", "", "rating");
        check(avatar.local.isEmpty() && avatar.online.size() == 2, "'Avatar' -> 2 online results");
        SearchResult rrr = catalog.search("RRR", "", "", "rating");
        check(rrr.local.size() == 1 && rrr.online.isEmpty(), "'RRR': library copy shown, no duplicate");
        SearchResult potter = catalog.search("Harry Potter", "", "", "rating");
        check(potter.online.size() == 2, "'Harry Potter' -> 2 online results");
        SearchResult inception = catalog.search("inception", "", "", "rating");
        check(inception.local.size() == 1 && inception.online.isEmpty(), "'inception' (library) -> no duplicate online");
        SearchResult none = catalog.search("zzzzqqqq", "", "", "rating");
        check(none.local.isEmpty() && none.online.isEmpty() && none.apiStatus.equals("ok"), "unknown title -> empty result, status ok");
        SearchResult filtered = catalog.search("Home Alone", "Horror", "", "rating");
        check(filtered.online.isEmpty(), "genre filter applies to online results");
        SearchResult lang = catalog.search("Avatar", "", "French", "rating");
        check(lang.online.isEmpty(), "language filter applies to online results");
        SearchResult browse = catalog.search("", "", "", "rating");
        check(browse.local.size() == repo.all().size() && browse.online.isEmpty() && browse.apiStatus.equals("idle"),
                "empty query -> whole library, no API call");

        SearchResult kgf = catalog.search("KGF", "", "", "rating");
        check(kgf.online.size() == 1 && kgf.online.get(0).getTitle().startsWith("K.G.F") && kgf.online.get(0).getYear() == 2018,
                "acronym 'KGF' finds 'K.G.F: Chapter 1' (dotted retry)");
        SearchResult kgfLower = catalog.search("kgf", "", "", "rating");
        check(kgfLower.online.size() == 1, "acronym search is case-insensitive");

        // --- ranking: exact title first (TMDB orders by popularity, so "Home Sweet Home Alone" can come first) ---
        // film(title, year, poster): year 0 / no poster imitate the obscure records real TMDB has
        java.util.function.Function<Object[], Movie> film = x ->
                new Movie(Movie.EXTERNAL_OFFSET + ((String) x[0]).length() * 100 + ((Integer) x[1]) % 100, (String) x[0], (Integer) x[1], 6.0,
                        "English", java.util.Collections.<String>emptyList(), java.util.Collections.<String>emptyList(), "", "",
                        (String) x[2], java.util.Collections.<String>emptyList(), 1, false, x.length > 3 ? (Integer) x[3] : 0);
        List<Movie> raw = java.util.Arrays.asList(
                film.apply(new Object[] {"Home, Alone", 0, null}),                 // junk record, punctuation differs
                film.apply(new Object[] {"Home Sweet Home Alone", 2021, "/a.jpg"}),
                film.apply(new Object[] {"Home Alone", 0, null}),                  // literal title but bare record
                film.apply(new Object[] {"Alone at Home", 2000, "/b.jpg"}),
                film.apply(new Object[] {"Home Alone 2: Lost in New York", 1992, "/c.jpg"}),
                film.apply(new Object[] {"Home Alone", 2018, "/e.jpg", 3}),        // obscure film with the same title, few votes
                film.apply(new Object[] {"Home Alone", 1990, "/d.jpg", 11000}));   // the real film, many votes
        List<String> ranked = new java.util.ArrayList<>();
        List<Movie> rankedMovies = MovieCatalog.rankByTitle("home alone", raw);
        for (Movie m : rankedMovies) ranked.add(m.getTitle() + "(" + m.getYear() + ")");
        check(ranked.get(0).equals("Home Alone(1990)") && ranked.get(1).equals("Home Alone(2018)") && ranked.get(2).equals("Home Alone(0)")
                        && ranked.get(3).equals("Home, Alone(0)") && ranked.get(4).startsWith("Home Alone 2")
                        && ranked.get(5).equals("Home Sweet Home Alone(2021)"),
                "ranking: identical title first (real films before bare records), then punctuation variants, then prefix matches: " + ranked);

        // --- caching ---
        int before = fake.searchCalls.get();
        catalog.search("home alone", "", "", "rating");
        catalog.search("Home Alone", "", "", "year");
        check(fake.searchCalls.get() == before, "repeated searches are served from cache (no extra API call)");

        // --- details ---
        Movie full = catalog.getDetailed(homeAlone.getId());
        check(full.isDetailed() && full.getDirector().equals("Chris Columbus"), "details: director = " + full.getDirector());
        check(full.getCast().size() == 4 && full.getCast().get(0).equals("Macaulay Culkin"), "details: cast " + full.getCast());
        check(full.getKeywords().contains("christmas") && full.getGenres().contains("Comedy"), "details: keywords and genres");
        int detailCalls = fake.detailCalls.get();
        catalog.getDetailed(homeAlone.getId());
        check(fake.detailCalls.get() == detailCalls, "details cached after the first load");
        check(catalog.get(Movie.EXTERNAL_OFFSET + 672) != null, "unknown online id is fetched on demand");
        check(catalog.get(Movie.EXTERNAL_OFFSET + 5) == null, "non-existent online id -> null");

        // --- posters for library movies ---
        Movie shawshank = repo.get(1);
        check(catalog.posterUrl(shawshank, "w342").equals("api/poster?id=1&size=w342"), "library movie poster goes through /api/poster");
        String img = catalog.resolvePosterImage(1, "w342");
        check(img != null && img.equals(fake.imageUrl() + "w342/shawshank.jpg"), "library poster resolved via title+year: " + img);
        int searches = fake.searchCalls.get();
        catalog.resolvePosterImage(1, "w500");
        check(fake.searchCalls.get() == searches, "library poster lookup cached");
        check(catalog.resolvePosterImage(2, "w342") == null, "movie with no poster -> null (UI shows 'No Poster Available')");
        Movie obscure = catalog.search("obscure", "", "", "rating").online.get(0);
        check(catalog.posterUrl(obscure, "w342") == null, "online movie without poster -> null");

        // --- poster files saved in webapp/images/posters (no API key needed) ---
        Movie saved = withPosters.get(1);
        check("images/posters/1.jpg".equals(saved.getPosterPath()), "movies.txt gives movie 1 a poster path: " + saved.getPosterPath());
        int withFile = 0, fileExists = 0;
        for (Movie m : withPosters.all()) {
            if (m.getPosterPath() == null) continue;
            withFile++;
            if (java.nio.file.Files.size(java.nio.file.Paths.get("webapp", m.getPosterPath())) > 5000) fileExists++;
        }
        check(withFile == withPosters.all().size() && fileExists == withFile, "every library movie has a real poster file on disk (" + fileExists + "/" + withPosters.all().size() + ")");
        check(new MovieCatalog(withPosters, null).posterUrl(saved, "w342").equals("images/posters/1.jpg"), "local poster is used even with no API key");
        check(catalog.posterUrl(saved, "w500").equals("images/posters/1.jpg"), "local poster is used first when a key is configured too");

        // --- recommendations with online movies ---
        UserProfile fan = new UserProfile();
        fan.toggleFavorite(homeAlone.getId());
        RecommendationService rec = new RecommendationService();
        List<Result> recs = rec.recommend(fan, repo.all(), 6, catalog::get);
        check(recs.size() == 6 && recs.stream().anyMatch(r -> r.movie.getGenres().contains("Comedy")),
                "online favourite drives recommendations: " + recs.get(0).movie.getTitle() + ", " + recs.get(1).movie.getTitle());
        check(recs.get(0).reason.contains("Home Alone"), "reason mentions the online movie: " + recs.get(0).reason);
        List<Result> sim = rec.similarTo(full, repo.all(), 6);
        check(sim.size() == 6 && sim.get(0).movie.getGenres().contains("Comedy"),
                "similar to Home Alone: " + sim.get(0).movie.getTitle() + ", " + sim.get(1).movie.getTitle());
        UserProfile other = new UserProfile();
        other.toggleFavorite(Movie.EXTERNAL_OFFSET + 19995);   // Avatar (sci-fi / adventure)
        catalog.get(Movie.EXTERNAL_OFFSET + 19995);
        List<Result> recs2 = rec.recommend(other, repo.all(), 6, catalog::get);
        check(!recs.get(0).movie.equals(recs2.get(0).movie), "different online favourite -> different top recommendation");

        // --- errors ---
        MovieCatalog badKey = new MovieCatalog(repo, new TmdbClient(new ApiConfig("wrong-key", fake.baseUrl(), fake.imageUrl())));
        SearchResult denied = badKey.search("Home Alone", "", "", "rating");
        check(denied.apiStatus.equals("error") && denied.apiMessage.contains("MOVIE_API_KEY") && denied.online.isEmpty(),
                "wrong key -> clear error message: " + denied.apiMessage);
        check(!badKey.search("Titanic", "", "", "rating").local.isEmpty(), "library results still returned when the API fails");

        MovieCatalog offline = new MovieCatalog(repo, new TmdbClient(new ApiConfig(FakeTmdbServer.KEY, "http://127.0.0.1:1/3", fake.imageUrl())));
        SearchResult down = offline.search("Home Alone", "", "", "rating");
        check(down.apiStatus.equals("error") && down.apiMessage.contains("Could not reach"), "server unreachable -> friendly message: " + down.apiMessage);

        MovieCatalog noKey = new MovieCatalog(repo, null);
        SearchResult off = noKey.search("Home Alone", "", "", "rating");
        check(off.apiStatus.equals("disabled") && off.apiMessage.contains("MOVIE_API_KEY"), "no key -> 'disabled' status with setup hint");
        check(noKey.posterUrl(shawshank, "w342") == null, "no key -> no poster url for library movies");
        check(noKey.get(Movie.EXTERNAL_OFFSET + 771) == null, "no key -> online ids cannot be loaded");

        // --- config ---
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("cfg", ".properties");
        // a file saved by Windows Notepad: UTF-8 BOM, CRLF line ends, spaces around '=', quoted value
        String notepad = "\uFEFF# comment\r\n  MOVIE_API_KEY = \"fake-key-123\"  \r\n";
        java.nio.file.Files.write(tmp, notepad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ApiConfig fromBom = new ApiConfig(ApiConfig.fromFile(tmp, null).getProperty("MOVIE_API_KEY"), "http://x", "http://y");
        check(fromBom.hasKey() && fromBom.apiKey().equals("fake-key-123"), "config file with BOM, spaces, quotes and CRLF is read correctly");
        java.nio.file.Files.write(tmp, "TMDB_API_KEY=alias-key\n".getBytes());
        check("alias-key".equals(ApiConfig.fromFile(tmp, null).getProperty("TMDB_API_KEY")), "TMDB_API_KEY alias is read");
        java.nio.file.Files.write(tmp, "MOVIE_API_KEY=PASTE_YOUR_KEY_HERE\n".getBytes());
        check(!new ApiConfig(ApiConfig.fromFile(tmp, null).getProperty("MOVIE_API_KEY"), "http://x", "http://y").hasKey(), "placeholder text is not accepted as a key");
        java.util.List<String> rep = new java.util.ArrayList<>();
        java.nio.file.Files.write(tmp, "SOME_OTHER=1\n".getBytes());
        ApiConfig.fromFile(tmp, rep);
        check(rep.get(0).contains("SOME_OTHER") && !rep.get(0).contains("fake-key"), "report names the properties it found (and never a key)");
        java.nio.file.Files.delete(tmp);
        check(!new ApiConfig("", "http://x", "http://y").hasKey(), "empty key = no key");
        check(!new ApiConfig("PASTE_YOUR_KEY_HERE", "http://x", "http://y").hasKey(), "placeholder key = no key");
        check(new ApiConfig("abc123", "http://x/", "http://y").baseUrl().equals("http://x"), "base url normalised");
    }
}
