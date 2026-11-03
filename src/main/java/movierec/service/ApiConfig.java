package movierec.service;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads the movie API settings. The key is looked up (first match wins) in:
 *   1. environment variable MOVIE_API_KEY
 *   2. JVM property MOVIE_API_KEY
 *   3. the file movie-api.properties (project root, or the path in env MOVIE_API_CONFIG)
 * It is only ever used on the server and is never sent to the browser or written to a log.
 */
public class ApiConfig {
    public static final String DEFAULT_BASE_URL = "https://api.themoviedb.org/3";
    public static final String DEFAULT_IMAGE_URL = "https://image.tmdb.org/t/p/";
    private static final String[] KEY_NAMES = {"MOVIE_API_KEY", "TMDB_API_KEY"};

    private final String apiKey;
    private final String baseUrl;
    private final String imageUrl;
    private final List<String> report = new ArrayList<>();      // what was looked at (never contains the key)

    public ApiConfig(String apiKey, String baseUrl, String imageUrl) {
        this.apiKey = clean(apiKey);
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.imageUrl = imageUrl.endsWith("/") ? imageUrl : imageUrl + "/";
    }

    public static ApiConfig load() {
        List<String> report = new ArrayList<>();
        Properties file = new Properties();
        for (Path c : candidateFiles()) {
            if (c == null) continue;
            if (!Files.isRegularFile(c)) { report.add("not found: " + c.toAbsolutePath()); continue; }
            file = fromFile(c, report);
            break;
        }
        String key = "";
        String source = "";
        for (String name : KEY_NAMES) {
            String v = System.getenv(name);
            if (!isBlank(v)) { key = v; source = "environment variable " + name; break; }
            v = System.getProperty(name);
            if (!isBlank(v)) { key = v; source = "JVM property " + name; break; }
            v = file.getProperty(name);
            if (!isBlank(v)) { key = v; source = "file property " + name; break; }
        }
        ApiConfig config = new ApiConfig(key,
                setting("MOVIE_API_BASE_URL", file, DEFAULT_BASE_URL),
                setting("MOVIE_IMAGE_BASE_URL", file, DEFAULT_IMAGE_URL));
        config.report.addAll(report);
        config.fileFound = !file.isEmpty() || report.stream().anyMatch(r -> r.startsWith("read file:"));
        if (!config.hasKey()) {
            config.report.add(config.apiKey.isEmpty() ? "no MOVIE_API_KEY value found anywhere"
                    : "MOVIE_API_KEY is still the placeholder text - paste your real key");
        } else {
            config.report.add("MOVIE_API_KEY found in " + source + " (" + config.apiKey.length() + " characters)");
        }
        return config;
    }

    /** Parses a properties file; tolerates a UTF-8 BOM (added by Notepad), quotes and spaces. */
    public static Properties fromFile(Path file, List<String> report) {
        Properties p = new Properties();
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            p.load(new StringReader(text.replace("\\", "\\\\")));      // keep Windows paths / backslashes literal
            if (report != null) {
                List<String> names = new ArrayList<>();
                for (Object k : p.keySet()) names.add(String.valueOf(k));
                report.add("read file: " + file.toAbsolutePath() + " (properties: " + names + ")");
            }
        } catch (IOException e) {
            if (report != null) report.add("could not read " + file.toAbsolutePath() + ": " + e.getMessage());
        }
        return p;
    }

    private static List<Path> candidateFiles() {
        List<Path> list = new ArrayList<>();
        String custom = System.getenv("MOVIE_API_CONFIG");
        if (!isBlank(custom)) list.add(Paths.get(custom));
        String base = System.getProperty("catalina.base");
        for (String name : new String[] {"movie-api.properties", "movie-api.properties.txt"}) {
            list.add(Paths.get(name));
            if (base != null) list.add(Paths.get(base).resolve("../../" + name).normalize());
        }
        return list;
    }

    private static String setting(String name, Properties file, String def) {
        String v = System.getenv(name);
        if (isBlank(v)) v = System.getProperty(name);
        if (isBlank(v)) v = file.getProperty(name);
        return isBlank(v) ? def : clean(v);
    }

    /** Trims spaces and surrounding quotes. */
    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private boolean fileFound = false;

    /** Short, user-facing explanation of why online search is off (and what to do). Never contains the key. */
    public String setupHint() {
        if (hasKey()) return "";
        if (apiKey.startsWith("PASTE_")) {
            return "Online search is off: movie-api.properties still contains the placeholder. Run setup-key.bat (or replace "
                    + "PASTE_YOUR_KEY_HERE with your TMDB key), then restart run.bat.";
        }
        if (fileFound) {
            return "Online search is off: movie-api.properties has no MOVIE_API_KEY=... line. Run setup-key.bat, then restart run.bat.";
        }
        return "Online search is off: no MOVIE_API_KEY found. Run setup-key.bat (or copy movie-api.properties.example to "
                + "movie-api.properties and add your TMDB key), then restart run.bat.";
    }

    public boolean hasKey() { return !apiKey.isEmpty() && !apiKey.startsWith("PASTE_"); }
    /** Only the server may call this. */
    public String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }
    public String imageUrl() { return imageUrl; }
    /** Human-readable account of where the loader looked. Never contains the key itself. */
    public List<String> report() { return report; }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String stripTrailingSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
}
