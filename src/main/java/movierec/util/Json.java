package movierec.util;

import java.util.Collection;
import movierec.model.Movie;

/** Tiny hand-written JSON writer (no external libraries). */
public final class Json {
    private Json() {}

    public static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static String strArray(Collection<String> items) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String s : items) {
            if (!first) sb.append(',');
            sb.append(str(s));
            first = false;
        }
        return sb.append(']').toString();
    }

    /**
     * Movie as JSON. userRating is 0 when the user has not rated it.
     * poster / posterLarge are URLs the browser can load, or null when there is no poster.
     */
    public static String movie(Movie m, int userRating, boolean favorite, String poster, String posterLarge) {
        return "{\"id\":" + m.getId()
                + ",\"title\":" + str(m.getTitle())
                + ",\"year\":" + m.getYear()
                + ",\"rating\":" + m.getRating()
                + ",\"language\":" + str(m.getLanguage())
                + ",\"genres\":" + strArray(m.getGenres())
                + ",\"keywords\":" + strArray(m.getKeywords())
                + ",\"director\":" + str(m.getDirector())
                + ",\"cast\":" + strArray(m.getCast())
                + ",\"description\":" + str(m.getDescription())
                + ",\"poster\":" + str(poster)
                + ",\"posterLarge\":" + str(posterLarge)
                + ",\"source\":" + str(m.isExternal() ? "online" : "library")
                + ",\"userRating\":" + userRating
                + ",\"favorite\":" + favorite + "}";
    }
}
