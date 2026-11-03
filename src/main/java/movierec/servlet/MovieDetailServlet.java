package movierec.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import movierec.model.Movie;
import movierec.service.UserProfile;

/** GET /api/movie?id=5 -> the movie plus the most similar movies. */
@WebServlet("/api/movie")
public class MovieDetailServlet extends BaseServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer id = intParam(req, "id");
        Movie m = id == null ? null : catalog().getDetailed(id);
        if (m == null) { error(resp, 404, "Movie not found"); return; }
        UserProfile u = profile(req);
        json(resp, 200, "{\"movie\":" + movieJson(m, u)
                + ",\"similar\":" + resultList(recommender().similarTo(m, repo().all(), 6), u) + "}");
    }
}
