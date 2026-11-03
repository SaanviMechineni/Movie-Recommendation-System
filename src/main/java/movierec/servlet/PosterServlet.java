package movierec.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * GET /api/poster?id=6&size=w342
 * Finds the real poster of a library movie (looked up once, then cached) and redirects the
 * browser to the image. Responds 404 when the movie has no poster.
 */
@WebServlet("/api/poster")
public class PosterServlet extends BaseServlet {
    private static final List<String> SIZES = Arrays.asList("w185", "w342", "w500", "w780");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer id = intParam(req, "id");
        String size = req.getParameter("size");
        if (size == null || !SIZES.contains(size)) size = "w342";
        String url = id == null ? null : catalog().resolvePosterImage(id, size);
        if (url == null) {
            resp.setHeader("Cache-Control", "public, max-age=300");
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setHeader("Cache-Control", "public, max-age=86400");
        resp.sendRedirect(url);
    }
}
