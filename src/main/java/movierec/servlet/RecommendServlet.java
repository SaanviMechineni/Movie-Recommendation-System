package movierec.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import movierec.service.UserProfile;

/** GET /api/recommendations -> personalised movies with score and reason. */
@WebServlet("/api/recommendations")
public class RecommendServlet extends BaseServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        UserProfile u = profile(req);
        boolean personalised = !u.getFavorites().isEmpty() || !u.getRatings().isEmpty();
        json(resp, 200, "{\"personalised\":" + personalised + ",\"results\":"
                + resultList(recommender().recommend(u, repo().all(), 12, catalog()::get), u) + "}");
    }
}
