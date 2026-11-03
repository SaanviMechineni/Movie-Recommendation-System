package movierec.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import movierec.service.MovieCatalog;
import movierec.service.UserProfile;
import movierec.util.Json;

/**
 * GET /api/search?q=home%20alone&genre=&language=&sort=
 *
 * Browser -> this servlet -> online movie API -> this servlet -> JSON -> browser.
 * Library movies come first; matching movies from the online database are added after them.
 * The API key stays on the server.
 *
 * Response: {"query":"..","local":[movies],"online":[movies],"api":{"status":"ok|idle|disabled|error","message":".."}}
 */
@WebServlet("/api/search")
public class MovieSearchServlet extends BaseServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String q = req.getParameter("q") == null ? "" : req.getParameter("q").trim();
        if (q.length() > 100) q = q.substring(0, 100);
        UserProfile u = profile(req);

        MovieCatalog.SearchResult r = catalog().search(q, req.getParameter("genre"),
                req.getParameter("language"), req.getParameter("sort"));

        json(resp, 200, "{\"query\":" + Json.str(q)
                + ",\"local\":" + movieList(r.local, u)
                + ",\"online\":" + movieList(r.online, u)
                + ",\"api\":{\"status\":" + Json.str(r.apiStatus) + ",\"message\":" + Json.str(r.apiMessage) + "}}");
    }
}
