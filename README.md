# MovieRec - Movie Recommendation System

Plain Java + Servlets (Jakarta Servlet 6 / Tomcat 10.1) + HTML + CSS + vanilla JavaScript.
No frameworks, no database, no build tool - just `javac`.

## Run
```
test.bat   # compile + run all unit tests (recommender + online search)
run.bat    # compile + start Tomcat -> open http://localhost:8080/
```
Requires a JDK (17+). Tomcat is already unpacked in `tools/apache-tomcat-10.1.34`.

## Posters
The 56 library movies ship with real posters in `webapp/images/posters/{id}.jpg` (the path is the 10th column of `movies.txt`), so posters work with **no API key**.
They were downloaded once from Wikipedia by `poster-download.bat` (`movierec.util.PosterDownloader`): it asks the Wikipedia summary API for
`Title_(Year_film)`, then `Title_(film)`, nearby years, language variants and the plain title; accepts a page only if its description says "film" and its year matches;
then saves the lead image scaled to 500px wide. Re-run it (`--force`, or `--only=12,30`) after adding movies. If a movie fails, add its exact Wikipedia page title to
`OVERRIDES` in `PosterDownloader.java`. Movies from the online search use the TMDB poster instead (needs the key). If an image file is genuinely missing, the card shows "No Poster Available".

## Turn on search for ANY movie (needs a free TMDB key)
Without a key the site still works, but only searches the 56-movie library and shows
"No Poster Available" for posters. With a key it searches every movie and shows real posters.

1. Create a free account at https://www.themoviedb.org and open **Settings -> API**
   (https://www.themoviedb.org/settings/api). Request an API key (choose "Developer", personal use).
2. Easiest: double-click **`setup-key.bat`** (or run it in your own terminal). It asks for the key with hidden input and
   writes `movie-api.properties` for you. Then skip to step 4.
   Manual way: open **`movie-api.properties`** (same folder as `run.bat`; copy it from `movie-api.properties.example` if missing).
3. Replace `PASTE_YOUR_KEY_HERE` with your key (the file name must be exactly `movie-api.properties`, not `.txt`):
   ```
   MOVIE_API_KEY=PASTE_YOUR_KEY_HERE
   ```
   Either the short "API Key" or the long "API Read Access Token" (starts with `eyJ`) works.
4. Restart `run.bat`. The Tomcat log should say `Online movie search ENABLED`.

Alternative: set an environment variable instead of the file (`set MOVIE_API_KEY=...` before `run.bat`).
Lookup order: environment variable, then `movie-api.properties`.

The key is only read by the Java server. The browser talks to `/api/...` on your own server and
never sees the key. `movie-api.properties` is listed in `.gitignore` - do not commit it.

## How search works
```
Browser -> GET /api/search?q=home alone -> MovieSearchServlet -> MovieCatalog
                                                                   |-- library (movies.txt) first
                                                                   |-- then TmdbClient -> TMDB API
Browser <- JSON {local:[...], online:[...], api:{status,message}} <-+
```
* Library matches are shown first; matches from TMDB are added after them (duplicates hidden).
* If TMDB fails (no internet, bad key) the library results are still shown plus an error banner.
* Clicking an online movie calls `/api/movie?id=` which loads the full details (director, cast,
  keywords) from TMDB.
* Online movies get id = 10,000,000 + TMDB id so they never clash with library ids.
* Posters: library movies use their saved file (see Posters above). Online movies use the TMDB image URL directly.
  A library movie with no saved file falls back to `/api/poster?id=`, which looks the poster up on TMDB once, caches it and redirects.
* Caches (in memory, `MovieCatalog`): search results for 30 min, full movie details, poster paths.
  Restarting the server empties them.

## Project layout
```
src/main/java/movierec/
  model/Movie.java                    data class (library and online movies)
  data/MovieRepository.java           loads movies.txt, library search + filters
  service/ApiConfig.java              reads MOVIE_API_KEY (env var or movie-api.properties)
  service/TmdbClient.java             calls TMDB and converts its JSON into Movie objects
  service/MovieCatalog.java           library + online lookup, search combining, caches
  service/UserProfile.java            favourites + 1-5 star ratings (kept in the HttpSession)
  service/RecommendationService.java  the recommendation algorithm
  servlet/                            JSON servlets (below)
  util/Json.java, JsonParser.java     tiny JSON writer / reader
  util/PosterDownloader.java          one-time poster downloader (poster-download.bat)
src/test/java/movierec/
  RecommenderTest.java                dataset, search, algorithm checks
  ApiSearchTest.java                  online search checks (uses FakeTmdbServer, works offline)
  FakeTmdbServer.java                 pretend TMDB for tests
webapp/
  index.html  movie.html  favorites.html  recommendations.html
  css/style.css  js/app.js
  images/posters/                    56 downloaded posters ({id}.jpg)
  WEB-INF/data/movies.txt             library dataset (56 movies, 11 languages)
```

## API
| URL | Method | Purpose |
|---|---|---|
| `/api/search?q=&genre=&language=&sort=` | GET | library + online search (empty `q` = whole library) |
| `/api/movies?meta=1` | GET | genres / languages for the filter dropdowns |
| `/api/movie?id=` | GET | details (+ cast/director for online movies) and 6 similar library movies |
| `/api/poster?id=&size=` | GET | redirects to the real poster image, 404 if none |
| `/api/rate?id=&stars=` | POST | save 1-5 star rating (0 clears) |
| `/api/favorites` | GET / POST `?id=` | list watchlist / toggle a movie |
| `/api/recommendations` | GET | personalised picks with score and reason |

## Recommendation algorithm (content-based filtering)
For a liked movie **A** and candidate **B**:

```
similarity(A,B) = 0.45 * genre_overlap(A,B)      (Jaccard: common / total distinct genres)
                + 0.25 * keyword_overlap(A,B)    (Jaccard on keywords)
                + 0.15 * rating(B) / 10
                + 0.10 * (1 - |yearA - yearB| / 50)   (not below 0)
                + 0.05 * (same language ? 1 : 0)
```
The user's **liked movies** are their favourites (weight 5) and anything they rated 3+ stars
(weight = stars). A candidate's final score is the weighted average of its similarity to every
liked movie. Already favourited/rated movies are excluded, and the "reason" shown on each card
names the liked movie that matched best. With no favourites/ratings yet (cold start) the
top-rated movies are shown.

Online movies can be liked too: their genres, keywords, rating, year and language feed the same
formula. Recommendations and "similar movies" are always chosen from the library (56 movies), so
the results stay consistent; the movie you liked can come from anywhere.

Example: Jaccard of genres {Sci-Fi, Action, Thriller} and {Sci-Fi, Drama, Adventure} = 1 common / 5 total = 0.2.

## Notes
- Favourites and ratings live in the browser session (cookie), so they reset when the session ends or the server restarts.
- The unit tests never call the real TMDB: `FakeTmdbServer` imitates its URLs and JSON.
  To try the website against it: start `movierec.FakeTmdbServer 9099`, then run with
  `MOVIE_API_KEY=test-key`, `MOVIE_API_BASE_URL=http://127.0.0.1:9099/3`, `MOVIE_IMAGE_BASE_URL=http://127.0.0.1:9099/t/p/`.
- This product uses the TMDB API but is not endorsed or certified by TMDB.
