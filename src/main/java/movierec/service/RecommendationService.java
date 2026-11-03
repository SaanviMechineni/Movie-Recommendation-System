package movierec.service;

import java.util.*;
import movierec.model.Movie;

/**
 * Content-based recommender.
 *
 * similarity(A, B) = 0.45 * genreSimilarity
 *                  + 0.25 * keywordSimilarity
 *                  + 0.15 * ratingScore(B)
 *                  + 0.10 * yearSimilarity
 *                  + 0.05 * languageMatch   (1 if same language, else 0)
 *
 *  - genreSimilarity / keywordSimilarity = Jaccard = |common| / |union|
 *  - ratingScore(B)  = B.rating / 10
 *  - yearSimilarity  = 1 - |yearA - yearB| / 50   (never below 0)
 *
 * Personalised score of a candidate = weighted average of its similarity to every movie
 * the user liked (favourites, or rated 3+ stars). Weight = the user's star rating
 * (a favourite that was not rated counts as 5).
 */
public class RecommendationService {
    public static final double W_GENRE = 0.45;
    public static final double W_KEYWORD = 0.25;
    public static final double W_RATING = 0.15;
    public static final double W_YEAR = 0.10;
    public static final double W_LANGUAGE = 0.05;
    private static final int YEAR_SPAN = 50;

    /** One recommended movie and the reason it was picked. */
    public static class Result {
        public final Movie movie;
        public final double score;      // 0..1
        public final String reason;
        public Result(Movie movie, double score, String reason) {
            this.movie = movie; this.score = score; this.reason = reason;
        }
    }

    public static double jaccard(Collection<String> a, Collection<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        return (double) common.size() / union.size();
    }

    public static double yearSimilarity(int y1, int y2) {
        return Math.max(0, 1.0 - (double) Math.abs(y1 - y2) / YEAR_SPAN);
    }

    public static double similarity(Movie liked, Movie candidate) {
        return W_GENRE * jaccard(liked.getGenres(), candidate.getGenres())
                + W_KEYWORD * jaccard(liked.getKeywords(), candidate.getKeywords())
                + W_RATING * (candidate.getRating() / 10.0)
                + W_YEAR * yearSimilarity(liked.getYear(), candidate.getYear())
                + W_LANGUAGE * (liked.getLanguage().equals(candidate.getLanguage()) ? 1.0 : 0.0);
    }

    /** Movies most similar to one movie (used on the details page). */
    public List<Result> similarTo(Movie base, List<Movie> all, int limit) {
        List<Result> out = new ArrayList<>();
        for (Movie m : all) {
            if (m.getId() == base.getId()) continue;
            out.add(new Result(m, similarity(base, m), reasonFor("Similar to ", base, m)));
        }
        out.sort((x, y) -> Double.compare(y.score, x.score));
        return out.subList(0, Math.min(limit, out.size()));
    }

    public List<Result> recommend(UserProfile user, List<Movie> all, int limit) {
        Map<Integer, Movie> byId = new HashMap<>();
        for (Movie m : all) byId.put(m.getId(), m);
        return recommend(user, all, limit, byId::get);
    }

    /**
     * Personalised recommendations. Falls back to top-rated movies if the user has no likes yet.
     * lookup finds a liked movie by id (liked movies may come from the online database,
     * so they are not always in the candidate list).
     */
    public List<Result> recommend(UserProfile user, List<Movie> all, int limit,
                                  java.util.function.Function<Integer, Movie> lookup) {
        Map<Integer, Double> weights = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> e : likedWeights(user).entrySet()) {
            if (lookup.apply(e.getKey()) != null) weights.put(e.getKey(), e.getValue());
        }

        List<Result> out = new ArrayList<>();
        if (weights.isEmpty()) {
            for (Movie m : all) {
                if (user.getRating(m.getId()) > 0 || user.isFavorite(m.getId())) continue;
                out.add(new Result(m, m.getRating() / 10.0,
                        "Top rated pick - add favourites or rate movies to personalise this"));
            }
        } else {
            for (Movie cand : all) {
                int id = cand.getId();
                if (weights.containsKey(id) || user.isFavorite(id) || user.getRating(id) > 0) continue;
                double sum = 0, wsum = 0, best = -1;
                Movie bestLiked = null;
                for (Map.Entry<Integer, Double> e : weights.entrySet()) {
                    Movie liked = lookup.apply(e.getKey());
                    if (liked == null) continue;
                    double s = similarity(liked, cand);
                    sum += e.getValue() * s;
                    wsum += e.getValue();
                    if (s > best) { best = s; bestLiked = liked; }
                }
                if (wsum == 0) continue;
                out.add(new Result(cand, sum / wsum, bestLiked == null ? "" : reasonFor("Because you liked ", bestLiked, cand)));
            }
        }
        out.sort((x, y) -> Double.compare(y.score, x.score));
        return out.subList(0, Math.min(limit, out.size()));
    }

    /** movieId -> weight for every movie the user likes. */
    static Map<Integer, Double> likedWeights(UserProfile user) {
        Map<Integer, Double> w = new LinkedHashMap<>();
        for (int id : user.getFavorites()) w.put(id, 5.0);
        for (Map.Entry<Integer, Integer> e : user.getRatings().entrySet()) {
            if (e.getValue() >= 3) w.put(e.getKey(), (double) e.getValue());
            else w.remove(e.getKey());   // rated low -> not a liked movie even if favourite
        }
        return w;
    }

    static String reasonFor(String prefix, Movie liked, Movie cand) {
        List<String> shared = new ArrayList<>(cand.getGenres());
        shared.retainAll(liked.getGenres());
        List<String> kw = new ArrayList<>(cand.getKeywords());
        kw.retainAll(liked.getKeywords());
        StringBuilder sb = new StringBuilder(prefix).append(liked.getTitle());
        if (!shared.isEmpty()) sb.append(" - shared genres: ").append(String.join(", ", shared));
        if (!kw.isEmpty()) sb.append(shared.isEmpty() ? " - " : "; ").append("keywords: ").append(String.join(", ", kw));
        return sb.toString();
    }
}
