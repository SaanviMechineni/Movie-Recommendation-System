package movierec.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-visitor data (stored in the HttpSession): favorites and the ratings the user gave (1-5). */
public class UserProfile {
    private final Set<Integer> favorites = new LinkedHashSet<>();
    private final Map<Integer, Integer> ratings = new ConcurrentHashMap<>();

    public synchronized Set<Integer> getFavorites() { return new LinkedHashSet<>(favorites); }
    public synchronized boolean isFavorite(int id) { return favorites.contains(id); }

    /** Adds or removes a favorite; returns true if it is now a favorite. */
    public synchronized boolean toggleFavorite(int id) {
        if (!favorites.remove(id)) { favorites.add(id); return true; }
        return false;
    }

    public Map<Integer, Integer> getRatings() { return ratings; }
    public int getRating(int id) { return ratings.getOrDefault(id, 0); }
    public void setRating(int id, int stars) {
        if (stars <= 0) ratings.remove(id); else ratings.put(id, stars);
    }
}
