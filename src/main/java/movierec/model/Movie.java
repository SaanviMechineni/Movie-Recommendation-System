package movierec.model;

import java.util.Collections;
import java.util.List;

/**
 * A movie. Local movies come from movies.txt (ids 1..9999999).
 * Movies from the online database get id = EXTERNAL_OFFSET + tmdbId so ids never collide.
 */
public class Movie {
    public static final int EXTERNAL_OFFSET = 10_000_000;

    private final int id;
    private final String title;
    private final int year;               // 0 when unknown
    private final double rating;          // out of 10
    private final String language;
    private final List<String> genres;
    private final List<String> keywords;
    private final String director;
    private final String description;
    private final String posterPath;      // TMDB poster path like "/abc.jpg", or null
    private final List<String> cast;
    private final int tmdbId;             // 0 for local movies
    private final boolean detailed;       // true once full details (cast, keywords...) were loaded
    private final int voteCount;          // number of TMDB votes (0 for library movies) - a popularity signal

    public Movie(int id, String title, int year, double rating, String language,
                 List<String> genres, List<String> keywords, String director, String description) {
        this(id, title, year, rating, language, genres, keywords, director, description,
                null, Collections.<String>emptyList(), 0, false);
    }

    public Movie(int id, String title, int year, double rating, String language,
                 List<String> genres, List<String> keywords, String director, String description,
                 String posterPath, List<String> cast, int tmdbId, boolean detailed) {
        this(id, title, year, rating, language, genres, keywords, director, description,
                posterPath, cast, tmdbId, detailed, 0);
    }

    public Movie(int id, String title, int year, double rating, String language,
                 List<String> genres, List<String> keywords, String director, String description,
                 String posterPath, List<String> cast, int tmdbId, boolean detailed, int voteCount) {
        this.id = id;
        this.title = title;
        this.year = year;
        this.rating = rating;
        this.language = language;
        this.genres = genres;
        this.keywords = keywords;
        this.director = director;
        this.description = description;
        this.posterPath = posterPath;
        this.cast = cast;
        this.tmdbId = tmdbId;
        this.detailed = detailed;
        this.voteCount = voteCount;
    }

    public int getId() { return id; }
    public String getTitle() { return title; }
    public int getYear() { return year; }
    public double getRating() { return rating; }
    public String getLanguage() { return language; }
    public List<String> getGenres() { return genres; }
    public List<String> getKeywords() { return keywords; }
    public String getDirector() { return director; }
    public String getDescription() { return description; }
    public String getPosterPath() { return posterPath; }
    public List<String> getCast() { return cast; }
    public int getTmdbId() { return tmdbId; }
    public boolean isDetailed() { return detailed; }
    public int getVoteCount() { return voteCount; }
    public boolean isExternal() { return tmdbId != 0; }
}
