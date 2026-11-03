package movierec;

import java.io.FileInputStream;
import java.util.List;
import movierec.data.MovieRepository;
import movierec.model.Movie;
import movierec.service.RecommendationService;
import movierec.service.RecommendationService.Result;
import movierec.service.UserProfile;

/** Plain-Java checks (no JUnit needed). Run with test.bat; exits with code 1 on failure. */
public class RecommenderTest {
    private static int failures = 0;

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    private static List<Integer> ids(List<Result> rs) {
        List<Integer> out = new java.util.ArrayList<>();
        for (Result r : rs) out.add(r.movie.getId());
        return out;
    }

    private static String titles(List<Result> rs) {
        StringBuilder sb = new StringBuilder();
        for (Result r : rs.subList(0, Math.min(3, rs.size()))) sb.append(r.movie.getTitle()).append(", ");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "webapp/WEB-INF/data/movies.txt";
        MovieRepository repo = new MovieRepository(new FileInputStream(path));
        RecommendationService rec = new RecommendationService();

        // --- dataset ---
        check(repo.all().size() >= 50, "dataset has at least 50 movies (" + repo.all().size() + ")");
        boolean unique = repo.all().stream().map(Movie::getId).distinct().count() == repo.all().size();
        check(unique, "movie ids are unique");
        check(repo.all().stream().allMatch(m -> !m.getGenres().isEmpty() && !m.getKeywords().isEmpty()),
                "every movie has genres and keywords");
        check(repo.languages().size() >= 5, "multiple languages present " + repo.languages());

        // --- search & filters ---
        check(repo.search("inception", "", "").size() == 1, "search by title");
        check(repo.search("NOLAN", "", "").size() >= 4, "search by director is case-insensitive");
        check(repo.search("", "Horror", "").stream().allMatch(m -> m.getGenres().contains("Horror")), "genre filter");
        check(repo.search("", "", "Hindi").stream().allMatch(m -> m.getLanguage().equals("Hindi")), "language filter");
        check(!repo.search("", "Comedy", "Hindi").isEmpty(), "genre + language combined");
        check(repo.search("zzzz-nothing", "", "").isEmpty(), "no match returns empty");

        // --- similarity maths ---
        Movie inception = repo.get(6), interstellar = repo.get(8), toyStory = repo.get(15);
        check(Math.abs(RecommendationService.jaccard(List.of("a", "b"), List.of("b", "c")) - 1.0 / 3) < 1e-9, "jaccard = 1/3");
        check(RecommendationService.yearSimilarity(2000, 2000) == 1.0, "same year -> 1.0");
        check(RecommendationService.yearSimilarity(1950, 2020) == 0.0, "70 years apart -> 0");
        check(RecommendationService.similarity(inception, interstellar)
                > RecommendationService.similarity(inception, toyStory), "Inception is closer to Interstellar than Toy Story");

        // --- similar movies ---
        List<Result> similar = rec.similarTo(inception, repo.all(), 5);
        check(similar.size() == 5 && similar.stream().noneMatch(r -> r.movie.getId() == 6), "similarTo excludes itself");
        check(similar.get(0).score >= similar.get(4).score, "similarTo sorted by score");

        // --- personalised recommendations ---
        UserProfile cold = new UserProfile();
        List<Result> coldRecs = rec.recommend(cold, repo.all(), 5);
        check(coldRecs.get(0).movie.getRating() == 9.3, "cold start returns top rated movie first");

        UserProfile u = new UserProfile();
        u.toggleFavorite(6);      // Inception
        u.setRating(8, 5);        // Interstellar
        List<Result> recs = rec.recommend(u, repo.all(), 10);
        check(recs.stream().noneMatch(r -> r.movie.getId() == 6 || r.movie.getId() == 8), "does not recommend already-seen movies");
        boolean scifiTop = recs.subList(0, 3).stream().allMatch(r -> r.movie.getGenres().contains("Sci-Fi")
                || r.movie.getGenres().contains("Thriller"));
        check(scifiTop, "sci-fi fan gets sci-fi/thriller on top: " + recs.get(0).movie.getTitle() + ", "
                + recs.get(1).movie.getTitle() + ", " + recs.get(2).movie.getTitle());
        check(!recs.get(0).reason.isEmpty(), "reason text present: " + recs.get(0).reason);

        UserProfile disliker = new UserProfile();
        disliker.toggleFavorite(6);
        disliker.setRating(6, 1);   // favourite but rated 1 star -> no longer counts as liked
        check(rec.recommend(disliker, repo.all(), 3).get(0).movie.getRating() == 9.3,
                "favourite rated 1 star no longer counts as liked (falls back to top rated)");

        // --- different tastes must give different recommendations ---
        UserProfile horror = new UserProfile();  horror.toggleFavorite(17); horror.toggleFavorite(18);
        UserProfile kids = new UserProfile();    kids.toggleFavorite(14); kids.toggleFavorite(15); kids.toggleFavorite(16);
        UserProfile hindi = new UserProfile();   hindi.toggleFavorite(29); hindi.toggleFavorite(33); hindi.toggleFavorite(36);
        List<Result> rHorror = rec.recommend(horror, repo.all(), 6);
        List<Result> rKids = rec.recommend(kids, repo.all(), 6);
        List<Result> rHindi = rec.recommend(hindi, repo.all(), 6);
        check(rHorror.stream().anyMatch(r -> r.movie.getGenres().contains("Horror")),
                "horror fan is shown a horror movie: " + titles(rHorror));
        check(rKids.get(0).movie.getGenres().contains("Animation"),
                "animation fan's top pick is animated: " + titles(rKids));
        check(rHindi.get(0).movie.getGenres().contains("Comedy") || rHindi.get(0).movie.getGenres().contains("Drama"),
                "comedy-drama fan's top pick is comedy/drama: " + titles(rHindi));
        check(!ids(rHorror).equals(ids(rKids)) && !ids(rKids).equals(ids(rHindi)) && !ids(rHorror).equals(ids(rHindi)),
                "three different tastes give three different lists");

        // --- ratings change the result ---
        UserProfile base = new UserProfile();  base.toggleFavorite(6);
        List<Result> before = rec.recommend(base, repo.all(), 6);
        UserProfile loves = new UserProfile(); loves.toggleFavorite(6); loves.setRating(15, 5);   // loves Toy Story
        List<Result> afterLove = rec.recommend(loves, repo.all(), 6);
        check(!ids(before).equals(ids(afterLove)), "rating Toy Story 5 stars changes the list");
        check(afterLove.stream().anyMatch(r -> r.movie.getGenres().contains("Animation")), "...and brings in animated movies");
        check(before.stream().noneMatch(r -> r.movie.getGenres().contains("Animation")), "...which were not there before");
        UserProfile hates = new UserProfile(); hates.toggleFavorite(6); hates.setRating(15, 1);
        check(ids(rec.recommend(hates, repo.all(), 6)).stream().noneMatch(id -> id == 15)
                && rec.recommend(hates, repo.all(), 6).stream().noneMatch(r -> r.movie.getGenres().contains("Animation")),
                "1-star rating does not pull in similar movies");

        // --- more favourites -> list adapts; removing a favourite reverts ---
        UserProfile toggle = new UserProfile(); toggle.toggleFavorite(6);
        List<Integer> one = ids(rec.recommend(toggle, repo.all(), 6));
        toggle.toggleFavorite(17);
        List<Integer> two = ids(rec.recommend(toggle, repo.all(), 6));
        toggle.toggleFavorite(17);
        check(!one.equals(two), "adding a favourite changes the list");
        check(one.equals(ids(rec.recommend(toggle, repo.all(), 6))), "removing it restores the old list");

        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
