package movierec.util;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * One-time utility: downloads a real poster for every movie in movies.txt from Wikipedia
 * (no API key needed), saves it as webapp/images/posters/{id}.jpg and adds the path as the
 * 10th column of movies.txt.
 *
 * Run:  poster-download.bat            (movies that already have a poster are skipped)
 *       poster-download.bat --force    (download everything again)
 *       poster-download.bat --only=12,30   (only these movie ids)
 *
 * For a movie that FAILS, find its Wikipedia page title and add it to OVERRIDES below.
 */
public class PosterDownloader {
    private static final String USER_AGENT = "CineMatch-College-Project/1.0 (student project)";
    private static final String SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/";
    private static final int POSTER_WIDTH = 500;

    /** movie id -> exact Wikipedia page title, for films the automatic guesses cannot find. */
    private static final Map<Integer, String> OVERRIDES = new HashMap<>();
    static {
        // example: OVERRIDES.put(25, "Amélie");
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private static class Result {
        boolean ok; String url = ""; String note = ""; List<String> tried = new ArrayList<>();
    }

    public static void main(String[] args) throws Exception {
        Path moviesFile = Paths.get("webapp/WEB-INF/data/movies.txt");
        Path outDir = Paths.get("webapp/images/posters");
        boolean force = false;
        Set<Integer> only = new HashSet<>();
        for (String a : args) {
            if (a.equals("--force")) force = true;
            else if (a.startsWith("--only=")) for (String s : a.substring(7).split(",")) only.add(Integer.parseInt(s.trim()));
        }
        Files.createDirectories(outDir);

        List<String> lines = Files.readAllLines(moviesFile, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        int ok = 0, failed = 0, skipped = 0;
        List<String> failures = new ArrayList<>();

        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                out.add(line.startsWith("# id|") ? "# id|title|year|rating|language|genres (;)|keywords (;)|director|description|poster (relative path)" : line);
                continue;
            }
            String[] p = line.split("\\|", -1);
            int id = Integer.parseInt(p[0]);
            String title = p[1], language = p[4];
            int year = Integer.parseInt(p[2]);
            String existing = p.length > 9 ? p[9] : "";
            Path file = outDir.resolve(id + ".jpg");

            boolean wanted = only.isEmpty() || only.contains(id);
            if (!wanted || (!force && !existing.isEmpty() && Files.exists(file))) {
                out.add(line);
                skipped++;
                continue;
            }

            Result r = fetch(id, title, year, language, file);
            if (r.ok) {
                ok++;
                System.out.printf("%-4d %-34s OK      %s%s%n", id, title, r.url, r.note);
                String[] q = Arrays.copyOf(p, 10);
                q[9] = "images/posters/" + id + ".jpg";
                out.add(String.join("|", q));
            } else {
                failed++;
                failures.add(id + " " + title);
                System.out.printf("%-4d %-34s FAILED  tried: %s%n", id, title, String.join("  ", r.tried));
                out.add(line);
            }
            Thread.sleep(150);   // be polite to Wikipedia
        }

        Files.write(moviesFile, out, StandardCharsets.UTF_8);
        System.out.printf("%nDone: %d downloaded, %d failed, %d skipped%n", ok, failed, skipped);
        if (!failures.isEmpty()) System.out.println("FAILED: " + failures);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ one movie

    private static Result fetch(int id, String title, int year, String language, Path target) {
        Result r = new Result();
        for (String page : candidates(id, title, year, language)) {
            String url = SUMMARY + encode(page);
            r.tried.add(page);
            try {
                String body = get(url);
                if (body == null) continue;                       // 404
                Map<String, Object> m = JsonParser.obj(JsonParser.parse(body));
                String description = JsonParser.str(m.get("description"), "");
                if (!description.toLowerCase(Locale.ROOT).contains("film")) continue;   // not the movie's page
                if (!yearMatches(description, year)) continue;                          // a different film
                String original = JsonParser.str(JsonParser.obj(m.get("originalimage")).get("source"), "");
                String thumb = JsonParser.str(JsonParser.obj(m.get("thumbnail")).get("source"), "");

                BufferedImage img = null;
                String used = "";
                for (String imgUrl : new String[] {original, thumb}) {
                    if (imgUrl.isEmpty()) continue;
                    img = decode(download(imgUrl));
                    if (img != null) { used = imgUrl; break; }
                }
                if (img == null) continue;
                save(img, target);
                r.ok = true;
                r.url = "https://en.wikipedia.org/wiki/" + page;
                if (img.getWidth() > img.getHeight()) r.note = "   (landscape image - check it)";
                return r;
            } catch (Exception e) {
                r.tried.set(r.tried.size() - 1, page + " [" + e.getMessage() + "]");
            }
        }
        return r;
    }

    private static List<String> candidates(int id, String title, int year, String language) {
        List<String> c = new ArrayList<>();
        if (OVERRIDES.containsKey(id)) c.add(OVERRIDES.get(id));
        String t = title.replace(' ', '_');
        c.add(t + "_(" + year + "_film)");
        c.add(t + "_(film)");
        c.add(t + "_(" + (year - 1) + "_film)");
        c.add(t + "_(" + (year + 1) + "_film)");
        if (!language.equals("English")) {
            c.add(t + "_(" + year + "_" + language + "_film)");
            c.add(t + "_(" + language + "_film)");
        }
        c.add(t);
        return c;
    }

    /** If the description names a year, it must be within 1 of ours (so "Dune (1984)" is not used for 2021). */
    private static boolean yearMatches(String description, int year) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(18|19|20)\\d{2}\\b").matcher(description);
        if (!m.find()) return true;
        return Math.abs(Integer.parseInt(m.group()) - year) <= 1;
    }

    // ------------------------------------------------------------------ http + images

    /** Returns the body, or null for 404. */
    private static String get(String url) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<String> res = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 200) return res.body();
            if (res.statusCode() == 404) return null;
            if (res.statusCode() == 429) { Thread.sleep(2000L * (attempt + 1)); continue; }
            throw new IOException("HTTP " + res.statusCode());
        }
        throw new IOException("rate limited");
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<byte[]> res = HTTP.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 200) return res.body();
            if (res.statusCode() == 429) { Thread.sleep(2000L * (attempt + 1)); continue; }
            throw new IOException("image HTTP " + res.statusCode());
        }
        throw new IOException("rate limited");
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT).header("Accept", "*/*").build();
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));    // null for formats Java cannot read (svg, webp)
        } catch (IOException e) {
            return null;
        }
    }

    /** Scales to 500px wide (keeps the ratio) and saves as JPEG. */
    private static void save(BufferedImage src, Path target) throws IOException {
        int w = Math.min(POSTER_WIDTH, src.getWidth());
        int h = Math.round(src.getHeight() * (w / (float) src.getWidth()));
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);   // no alpha channel in JPEG
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        if (!ImageIO.write(rgb, "jpg", target.toFile())) throw new IOException("no JPEG writer");
    }

    private static String encode(String page) {
        return URLEncoder.encode(page, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
