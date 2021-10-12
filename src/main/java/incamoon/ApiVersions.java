package incamoon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.*;

public class ApiVersions {

    public static final int UPDATE_FREQUENCY_DAYS = 90;
    private static final int MISSING_VERSION_FREQUENCY_DAYS = 1;
    public static final String LAST_UPDATED = "last-updated";
    private final Logger LOG = Logger.getLogger(ApiVersions.class.getName());

    final Path filePath;
    final Properties apiVersions = new Properties();
    private ObservableList<String> versionList;

    public ApiVersions() {
        final String userHome = System.getProperty("user.home");
        this.filePath = Paths.get(userHome, ".eso-addon-deps", "api-versions.properties");
        if (Files.exists(filePath)) {
            try (final InputStream stream = Files.newInputStream(filePath, READ)) {
                apiVersions.load(stream);
                if (LocalDate.parse(apiVersions.getProperty(LAST_UPDATED, LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)), DateTimeFormatter.BASIC_ISO_DATE).isBefore(LocalDate.now().minusDays(UPDATE_FREQUENCY_DAYS))) {
                    tryUpdateFromWiki();
                }
            } catch (IOException e) {
                LOG.log(Level.SEVERE, e, () -> "Unable to read api-versions.properties file - friendly names will be unavailable");
            }
        } else {
            try (final InputStream stream = getClass().getResourceAsStream("api-versions.default.properties")) {
                apiVersions.load(stream);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, e, () -> "Unable to copy default properties");
            }
            writePropertiesFile();
            tryUpdateFromWiki();
        }
    }

    void tryUpdateFromWiki() {
        fetchWiki().thenAccept(success -> {
            if (success) {
                apiVersions.setProperty(LAST_UPDATED, LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
                if (!writePropertiesFile()) return;
                refresh();
            }
        });
    }

    private boolean writePropertiesFile() {
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, e, () -> "Unable to create propery store directories");
            return false;
        }
        try (final OutputStream stream = Files.newOutputStream(filePath, CREATE, WRITE)) {
            apiVersions.store(stream, "ESO API Versions");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, e, () -> "Unable to read api-versions.properties file - friendly names will be unavailable");
            return false;
        }
        return true;
    }

    private CompletionStage<Boolean> fetchWiki() {
        return fetch(URI.create("https://wiki.esoui.com/w/api.php?action=query&prop=revisions&titles=APIVersion&rvslots=*&rvprop=content&formatversion=2&format=json"))
                .thenApply(result -> result.get("query").get("pages").fields().next().getValue().get("revisions").get(0).get("*").asText())
                .thenApply(this::parseWiki)
                .exceptionally(e -> {
                    LOG.log(Level.SEVERE, e, () -> "Unable to get results from wiki");
                    return false;
                });
    }

    private boolean parseWiki(String page) {
        final Pattern reLiveVersion = Pattern.compile("== live API version ==\\n\\n===(\\d*)===", Pattern.MULTILINE);
        final Pattern rePTSVersion = Pattern.compile("== PTS API version ==\\n\\n===(\\d*)===", Pattern.MULTILINE);

        final Pattern reVersionSection = Pattern.compile("===(\\d*)===(.*?)(?:<br>|\n)\n\n", Pattern.DOTALL);

        final Pattern reSectionUpdate = Pattern.compile("^\\* '''Update''': ([^\\['\\n]*)", Pattern.MULTILINE);
        final Pattern reSectionFeature = Pattern.compile("^\\* '''Features''': ([^\\['\\n]*)", Pattern.MULTILINE);

        try {
            final Matcher reLiveMatcher = reLiveVersion.matcher(page);
            if (reLiveMatcher.find()) {
                final String liveVersion = reLiveMatcher.group(1);
                apiVersions.setProperty("live-version", liveVersion);
            }

            final Matcher rePTSMatcher = rePTSVersion.matcher(page);
            if (rePTSMatcher.find()) {
                final String ptsVersion = rePTSMatcher.group(1);
                apiVersions.setProperty("pts-version", ptsVersion);
            }

            final Matcher reSectionMatcher = reVersionSection.matcher(page);
            reSectionMatcher.results().forEach(matchResult -> {
                final String versionNumber = matchResult.group(1);
                final String section = matchResult.group(2);
                final Matcher updateMatcher = reSectionUpdate.matcher(section);
                if (updateMatcher.find()) {
                    final String update = updateMatcher.group(1).trim();
                    apiVersions.setProperty("v" + versionNumber + ".update", update);
                }
                final Matcher featureMatcher = reSectionFeature.matcher(section);
                if (featureMatcher.find()) {
                    final String feature = featureMatcher.group(1).trim();
                    apiVersions.setProperty("v" + versionNumber + ".feature", feature);
                }
            });
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e, () -> "Unable to parse Wiki response");
            return false;
        }

        return true;
    }

    private CompletableFuture<JsonNode> fetch(URI uri) {

        final ObjectMapper objectMapper = new ObjectMapper();

        final HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .build();

        return HttpClient.newHttpClient()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(s -> {
                    try {
                        return objectMapper.readTree(s);
                    } catch (JsonProcessingException e) {
                        LOG.log(Level.SEVERE, e, () -> "Unable to parse wiki response");
                        return null;
                    }
                }).exceptionally(e -> {
                    LOG.log(Level.SEVERE, e, () -> "Unable to get results from wiki");
                    return null;
                });
    }

    public void bind(ObservableList<String> vss) {
        this.versionList = vss;
        refresh();
    }

    public String getVersionFeature(int number) {
        return apiVersions.getProperty("v" + number + ".feature", String.valueOf(number));
    }

    private synchronized void refresh() {
        final String liveVersion = apiVersions.getProperty("live-version");
        if (liveVersion == null) {
            return;
        }
        final String ptsVersion = apiVersions.getProperty("pts-version");
        for (int i = 0; i < versionList.size(); i++) {
            String version = versionList.get(i);
            if (version.length() == 6) {
                final String feature = apiVersions.getProperty("v" + version + ".feature");
                final String update = apiVersions.getProperty("v" + version + ".update");
                final boolean isLive = version.equals(liveVersion);
                final boolean isPTS = version.equals(ptsVersion);
                if (feature != null && update != null) {
                    versionList.set(i, version + " (" + update + " " + feature + ") " + (isLive ? "[*]" : isPTS ? "[^]" : " "));
                } else {
                    if (LocalDate.parse(apiVersions.getProperty(LAST_UPDATED, LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)), DateTimeFormatter.BASIC_ISO_DATE).isBefore(LocalDate.now().minusDays(MISSING_VERSION_FREQUENCY_DAYS))) {
                        tryUpdateFromWiki();
                    }
                }
            }
        }
    }
}
