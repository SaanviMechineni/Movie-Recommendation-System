package movierec.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** POST /api/rate?id=5&stars=4 -> save the user's 1-5 star rating (stars=0 clears it). */
@WebServlet("/api/rate")
public class RateServlet extends BaseServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer id = intParam(req, "id");
        Integer stars = intParam(req, "stars");
        if (id == null || catalog().get(id) == null) { error(resp, 404, "Movie not found"); return; }
        if (stars == null || stars < 0 || stars > 5) { error(resp, 400, "stars must be 0-5"); return; }
        profile(req).setRating(id, stars);
        json(resp, 200, "{\"id\":" + id + ",\"userRating\":" + stars + "}");
    }
}
