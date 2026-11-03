package movierec.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import movierec.model.Movie;
import movierec.service.UserProfile;

/**
 * GET  /api/favorites          -> the user's watchlist
 * POST /api/favorites?id=5     -> toggle a movie in/out of the watchlist
 */
@WebServlet("/api/favorites")
public class FavoritesServlet extends BaseServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        UserProfile u = profile(req);
        List<Movie> list = new ArrayList<>();
        for (int id : u.getFavorites()) {
            Movie m = catalog().get(id);
            if (m != null) list.add(m);
        }
        json(resp, 200, movieList(list, u));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer id = intParam(req, "id");
        if (id == null || catalog().get(id) == null) { error(resp, 404, "Movie not found"); return; }
        boolean now = profile(req).toggleFavorite(id);
        json(resp, 200, "{\"id\":" + id + ",\"favorite\":" + now + "}");
    }
}
