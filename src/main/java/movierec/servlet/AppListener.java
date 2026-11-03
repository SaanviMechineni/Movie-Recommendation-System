package movierec.servlet;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.io.InputStream;
import movierec.data.MovieRepository;
import movierec.service.ApiConfig;
import movierec.service.MovieCatalog;
import movierec.service.RecommendationService;
import movierec.service.TmdbClient;

/** Runs once when the web app starts: loads the dataset, the API settings, and shares them with all servlets. */
@WebListener
public class AppListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent e) {
        ServletContext ctx = e.getServletContext();
        try (InputStream in = ctx.getResourceAsStream("/WEB-INF/data/movies.txt")) {
            if (in == null) throw new IllegalStateException("movies.txt not found");
            MovieRepository repo = new MovieRepository(in);

            ApiConfig config = ApiConfig.load();
            TmdbClient tmdb = config.hasKey() ? new TmdbClient(config) : null;
            for (String line : config.report()) ctx.log("Movie API config: " + line);
            ctx.log(tmdb != null
                    ? "Online movie search ENABLED (API key found)."
                    : "Online movie search DISABLED - set MOVIE_API_KEY to enable it (see README).");

            ctx.setAttribute(BaseServlet.REPO, repo);
            ctx.setAttribute(BaseServlet.CATALOG, new MovieCatalog(repo, tmdb, config.setupHint()));
            ctx.setAttribute(BaseServlet.RECOMMENDER, new RecommendationService());
        } catch (Exception ex) {
            throw new IllegalStateException("Could not load movie dataset", ex);
        }
    }
}
