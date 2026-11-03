package movierec.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import movierec.model.Movie;
import movierec.util.Json;

/**
 * GET /api/movies?q=text&genre=Drama&language=Hindi&sort=rating|year|title
 * GET /api/movies?meta=1 -> list of genres and languages for the filter dropdowns.
 */
@WebServlet("/api/movies")
public class MoviesServlet extends BaseServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (req.getParameter("meta") != null) {
            json(resp, 200, "{\"genres\":" + Json.strArray(catalog().genres())
                    + ",\"languages\":" + Json.strArray(catalog().languages()) + "}");
            return;
        }
        List<Movie> list = new ArrayList<>(repo().search(
                req.getParameter("q"), req.getParameter("genre"), req.getParameter("language")));

        String sort = req.getParameter("sort");
        if ("year".equals(sort)) list.sort(Comparator.comparingInt(Movie::getYear).reversed());
        else if ("title".equals(sort)) list.sort(Comparator.comparing(Movie::getTitle));
        else list.sort(Comparator.comparingDouble(Movie::getRating).reversed());

        json(resp, 200, movieList(list, profile(req)));
    }
}
