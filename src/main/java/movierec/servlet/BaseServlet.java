package movierec.servlet;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import movierec.data.MovieRepository;
import movierec.model.Movie;
import movierec.service.MovieCatalog;
import movierec.service.RecommendationService;
import movierec.service.UserProfile;
import movierec.util.Json;

/** Shared helpers for all API servlets. */
public abstract class BaseServlet extends HttpServlet {
    static final String REPO = "movieRepo";
    static final String CATALOG = "movieCatalog";
    static final String RECOMMENDER = "recommender";
    private static final String PROFILE = "userProfile";

    protected MovieRepository repo() { return (MovieRepository) getServletContext().getAttribute(REPO); }
    protected MovieCatalog catalog() { return (MovieCatalog) getServletContext().getAttribute(CATALOG); }
    protected RecommendationService recommender() {
        return (RecommendationService) getServletContext().getAttribute(RECOMMENDER);
    }

    /** The visitor's favourites/ratings, kept in their session. */
    protected UserProfile profile(HttpServletRequest req) {
        HttpSession s = req.getSession(true);
        synchronized (s) {
            UserProfile p = (UserProfile) s.getAttribute(PROFILE);
            if (p == null) { p = new UserProfile(); s.setAttribute(PROFILE, p); }
            return p;
        }
    }

    protected String movieJson(Movie m, UserProfile u) {
        MovieCatalog c = catalog();
        return Json.movie(m, u.getRating(m.getId()), u.isFavorite(m.getId()),
                c.posterUrl(m, "w342"), c.posterUrl(m, "w500"));
    }

    /** Movie JSON with the recommendation score and reason appended. */
    protected String resultJson(RecommendationService.Result r, UserProfile u) {
        String base = movieJson(r.movie, u);
        return base.substring(0, base.length() - 1)
                + ",\"score\":" + Math.round(r.score * 1000) / 10.0
                + ",\"reason\":" + Json.str(r.reason) + "}";
    }

    protected String movieList(List<Movie> movies, UserProfile u) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < movies.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(movieJson(movies.get(i), u));
        }
        return sb.append(']').toString();
    }

    protected String resultList(List<RecommendationService.Result> results, UserProfile u) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(resultJson(results.get(i), u));
        }
        return sb.append(']').toString();
    }

    protected void json(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(body);
    }

    protected void error(HttpServletResponse resp, int status, String msg) throws IOException {
        json(resp, status, "{\"error\":" + Json.str(msg) + "}");
    }

    /** Parses an int request parameter, or returns null if missing/invalid. */
    protected Integer intParam(HttpServletRequest req, String name) {
        try { return Integer.valueOf(req.getParameter(name)); }
        catch (NumberFormatException ex) { return null; }
    }
}
