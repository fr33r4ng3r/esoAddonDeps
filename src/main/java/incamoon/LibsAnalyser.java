package incamoon;

import lombok.Builder;
import lombok.Value;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LibsAnalyser {

    private static final Logger LOG = Logger.getLogger(LibsAnalyser.class.getName());
    private static final BigDecimal MINUS_ONE = new BigDecimal(-1);

    @Value
    private static class FileIdent {
        String name;
        Path fInfo;
    }

    @Value
    public static class AddonIdent {
        String name;
        BigDecimal addonVersion;
    }

    @Value
    public static class VersionAndDirectory {
        BigDecimal version;
        Path directory;
    }

    @Builder(toBuilder = true, builderClassName = "Builder")
    public static class Addon {

        private static final Pattern reColor = Pattern.compile("(?:\\|c([0-9A-Fa-f]){6}|\\|r|\\|$)");

        String name;
        String title;
        String version;
        String apiVersion;
        BigDecimal addonVersion;
        Boolean isLib;
        List<Path> fInfo;
        Integer nestedLevel;
        List<AddonIdent> dependsOn;
        List<AddonIdent> optionalDependsOn;

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public List<AddonIdent> getDependsOn() {
            return Optional.ofNullable(dependsOn).orElseGet(Collections::emptyList);
        }

        public List<AddonIdent> getOptionalDependsOn() {
            return Optional.ofNullable(optionalDependsOn).orElseGet(Collections::emptyList);
        }

        public String getDependencies() {
            return Optional.ofNullable(dependsOn).map(d -> d.stream().map(AddonIdent::getName).collect(Collectors.joining(", "))).orElse("");
        }

        public String getOptionalDependencies() {
            return Optional.ofNullable(optionalDependsOn).map(d -> d.stream().map(AddonIdent::getName).collect(Collectors.joining(", "))).orElse("");
        }

        public Stream<String> getApiVersions() {
            return Optional.ofNullable(apiVersion).stream().flatMap(v -> Arrays.stream(v.split("[ ,]")).filter(s -> !s.isBlank()));
        }

        public Integer getApiVersion() {
            return Optional.ofNullable(apiVersion).flatMap(v -> Arrays.stream(v.split("[ ,]")).filter(s -> s.length() > 0).map(Integer::parseInt).max(Integer::compareTo)).orElse(0);
        }

        public String getTitle() {
            if (title == null) return name;
            final Matcher colorMatcher = reColor.matcher(title);
            if (colorMatcher.find()) {
                return colorMatcher.replaceAll("");
            }
            return title;
        }

        public boolean references(Addon dep) {
            if (dep == null) return false;
            return Stream.concat(this.getDependsOn().stream(), this.getOptionalDependsOn().stream()).anyMatch(a -> a.name.equals(dep.name));
        }

        public List<String> getPaths() {
            return fInfo.stream().map(p -> p.getParent().toString()).collect(Collectors.toList());
        }

        public boolean isEmbedded() {
            return nestedLevel > 0;
        }
    }

    public static final String DEPENDS_ON = "## DependsOn: ";
    public static final String OPTIONAL_DEPENDS_ON = "## OptionalDependsOn: ";
    public static final String API_VERSION = "## APIVersion: ";
    public static final String TITLE = "## Title: ";
    public static final String VERSION = "## Version: ";
    public static final String ADDON_VERSION = "## AddOnVersion: ";
    public static final String IS_LIBRARY = "## IsLibrary: ";

    private final String addonFolder;

    private final Map<String, List<VersionAndDirectory>> addonVersions = new HashMap<>();
    private final Map<AddonIdent, Addon> addons = new HashMap<>();

    public LibsAnalyser(String addonFolder) {
        this.addonFolder = addonFolder;
    }

    public void load(LogAppender logAppender) throws IOException {
        final Path path = Paths.get(addonFolder);
        if (!Files.exists(path)) {
            logAppender.log("ERROR! ESO Addon Path not found!" + addonFolder);
            throw new FileNotFoundException("ESO Addon Path not found!" + addonFolder);
        }
        load(path, 0, logAppender);
        logAppender.log("Loaded " + addons.size() + " AddOns");
        LOG.log(Level.INFO, "Loaded " + addons.size() + " AddOns");
    }

    public void load(Path path, int level, LogAppender logAppender) throws IOException {
        final List<FileIdent> failed = new LinkedList<>();
        Files.list(path).forEach(p -> {
            if (!Files.isDirectory(p)) {
                return;
            }
            final String name = p.getName(p.getNameCount() - 1).toString();
            if (name.startsWith(".")) {
                return;
            }
            final Path fInfo = p.resolve(name + ".txt");
            if (!Files.exists(fInfo)) {
                if (level == 0) {
                    LOG.log(Level.WARNING, "Addon " + name + " does not have an info txt file.  Ignoring");
                }
                try {
                    load(p, level + 1, logAppender);
                } catch (IOException e) {
                    logAppender.log("ERROR! Unable to list files in folder: " + p);
                    LOG.log(Level.SEVERE, e, () -> "Unable to list files in " + p);
                }
                return;
            }
            LOG.log(Level.INFO, "Loading: " + name);
            failed.addAll(read(name, fInfo, StandardCharsets.UTF_8, level, logAppender));
            LOG.log(Level.INFO, "Addon: " + name + " at " + Paths.get(addonFolder).relativize(fInfo) + " had " + addonVersions.getOrDefault(name, Collections.emptyList()).size() + " versions");

            try {
                load(p, level + 1, logAppender);
            } catch (IOException e) {
                logAppender.log("ERROR! Unable to list files in folder: " + p);
                LOG.log(Level.SEVERE, e, () -> "Unable to list files in " + p);
            }
        });
        failed.forEach(addon -> {
            LOG.log(Level.INFO, "Re-Loading: " + addon.name);
            final List<FileIdent> failedAgain = read(addon.name, addon.fInfo, StandardCharsets.ISO_8859_1, level, logAppender);
            failedAgain.forEach(f -> {
                LOG.log(Level.WARNING, "WARNING!  Failed to load Addon: " + f.name + " bad charset");
                logAppender.log("ERROR!  Failed to load Addon: " + f.name + " bad charset");
            });
        });
    }

    private List<FileIdent> read(String name, Path fInfo, Charset charset, int level, LogAppender logAppender) {
        final List<FileIdent> failed = new LinkedList<>();
        try (Stream<String> stream = Files.lines(fInfo, charset)) {
            try {
                final Addon.Builder addonBuilder = Addon.builder()
                        .name(name)
                        .fInfo(List.of(Paths.get(addonFolder).relativize(fInfo)))
                        .nestedLevel(level);

                stream.forEach(line -> {
                    if (line.startsWith(TITLE)) {
                        final String title = line.substring(TITLE.length());
                        addonBuilder.title(title);
                    } else if (line.startsWith(VERSION)) {
                        final String version = line.substring(VERSION.length());
                        addonBuilder.version(version);
                    } else if (line.startsWith(API_VERSION)) {
                        final String version = line.substring(API_VERSION.length());
                        addonBuilder.apiVersion(version);
                    } else if (line.startsWith(ADDON_VERSION)) {
                        final String version = line.substring(ADDON_VERSION.length());
                        try {
                            addonBuilder.addonVersion(new BigDecimal(version));
                        } catch (NumberFormatException e) {
                            logAppender.log("Addon + " + name + " has incorrect addon version number : " + version);
                            LOG.log(Level.WARNING, e, () -> "Addon + " + name + " has incorrect addon version number : " + version);
                        }
                    } else if (line.startsWith(IS_LIBRARY)) {
                        final String islib = line.substring(IS_LIBRARY.length());
                        addonBuilder.isLib("true".equalsIgnoreCase(islib));
                    } else if (line.startsWith(DEPENDS_ON)) {
                        final String[] deps = line.substring(DEPENDS_ON.length()).split("[ ,]");
                        addonBuilder.dependsOn(Arrays.stream(deps).map(String::trim).filter(s -> !s.isBlank()).map(d -> parseDependency(name, logAppender, d)).collect(Collectors.toList()));
                    } else if (line.startsWith(OPTIONAL_DEPENDS_ON)) {
                        final String[] deps = line.substring(OPTIONAL_DEPENDS_ON.length()).split("[ ,]");
                        addonBuilder.optionalDependsOn(Arrays.stream(deps).map(String::trim).filter(s -> !s.isBlank()).map(d -> parseDependency(name, logAppender, d)).collect(Collectors.toList()));
                    }
                });

                final Addon addon = addonBuilder.build();
                addonVersions.computeIfAbsent(addon.name, s -> new LinkedList<>()).add(new VersionAndDirectory(Optional.ofNullable(addon.addonVersion).orElse(BigDecimal.ZERO), fInfo.getParent()));
                addons.compute(
                        new AddonIdent(addon.name, addon.addonVersion),
                        (k, v) -> v == null ?
                                addon :
                                v.toBuilder()
                                        .fInfo(Stream.concat(v.fInfo.stream(), addon.fInfo.stream()).collect(Collectors.toList()))
                                        .nestedLevel(Math.min(addon.nestedLevel, v.nestedLevel))
                                        .build());

            } catch (UncheckedIOException e) {
                if (e.getCause() instanceof MalformedInputException) {
                    failed.add(new FileIdent(name, fInfo));
                } else {
                    logAppender.log("Error opening + " + fInfo.toString() + ".  " + e.getMessage());
                    LOG.log(Level.WARNING, "Error opening + " + fInfo.toString() + " bad charset.  skipping... (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            logAppender.log("Error opening + " + fInfo.toString() + ".  " + e.getMessage());
            LOG.log(Level.WARNING, "Error opening " + fInfo.toString() + " skipping...");
        }
        return failed;
    }

    private AddonIdent parseDependency(String name, LogAppender logAppender, String d) {
        if (d.indexOf(">=") > 0) {
            final String[] a = d.split(">=");
            try {
                return new AddonIdent(a[0], new BigDecimal(a[1]));
            } catch (NumberFormatException e) {
                logAppender.log("Addon + " + name + " has incorrect addon version number dependency: " + d);
                LOG.log(Level.WARNING, e, () -> "Addon + " + name + " has incorrect addon version number dependency : " + d);
                return new AddonIdent(a[0], BigDecimal.ZERO);
            }
        } else {
            return new AddonIdent(d, BigDecimal.ZERO);
        }
    }

    public List<Addon> getLibs() {
        return addons.values().stream().filter(a -> a.isLib != null && a.isLib).sorted(Comparator.comparing(Addon::getName)).collect(Collectors.toUnmodifiableList());
    }

    public List<Addon> getAddons() {
        return addons.values().stream().filter(a -> a.isLib == null || !a.isLib).sorted(Comparator.comparing(Addon::getName)).collect(Collectors.toUnmodifiableList());
    }

    public List<AddonIdent> getMissing() {
        final Stream<AddonIdent> allDependencies = addons.values().stream().flatMap(a -> a.getDependsOn().stream()).distinct();
        final Stream<AddonIdent> missingDependencies = allDependencies.filter(this::isAddonMissing);
        final Map<String, List<AddonIdent>> deduped = missingDependencies.collect(Collectors.groupingBy(AddonIdent::getName));
        return deduped.keySet().stream().map(k -> deduped.get(k).get(0)).sorted(Comparator.comparing(AddonIdent::getName)).collect(Collectors.toUnmodifiableList());
    }

    public List<AddonIdent> getDuplicates() {
        return addonVersions.entrySet().stream().filter(e -> e.getValue().size() > 1).map(e -> new AddonIdent(e.getKey(), e.getValue().stream().map(VersionAndDirectory::getVersion).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO))).collect(Collectors.toList());
    }

    public boolean isAddonMissing(AddonIdent dep) {
        final BigDecimal availableVersion = addonVersions.getOrDefault(dep.name, Collections.emptyList()).stream().map(VersionAndDirectory::getVersion).max(BigDecimal::compareTo).orElse(MINUS_ONE);
        return availableVersion.compareTo(dep.addonVersion) < 0;
    }

    public boolean isLibraryUnreferenced(Addon dep) {
        return addons.values().stream().flatMap(v -> Stream.concat(v.getDependsOn().stream(), v.getOptionalDependsOn().stream())).distinct().filter(a -> a.name.equals(dep.name)).findAny().isEmpty();
    }

    public boolean isDuplicate(Addon addon) {
        final List<VersionAndDirectory> duplicates = addonVersions.getOrDefault(addon.name, Collections.emptyList());
        final int potential = duplicates.size();
        if (potential <= 1) return false;
        final int selfNested = duplicates.stream().mapToInt(p1 -> duplicates.stream().mapToInt(p2 -> {
            if (!p1.directory.equals(p2.directory) && p2.directory.startsWith(p1.directory)) {
                return 1;
            } else {
                return 0;
            }
        }).sum()).sum();
        return (potential - selfNested) > 1;
    }

    public Integer getCount() {
        return addons.size();
    }

    public List<String> getVersions() {
        return addons.values().stream().flatMap(Addon::getApiVersions).map(v -> v.substring(0, 6)).distinct().sorted(Comparator.reverseOrder()).collect(Collectors.toList());
    }

    public void compress(Addon addon, LogAppender logAppender) {
        final Path adds = Paths.get(addonFolder);
        final Path root = adds.resolve(addon.fInfo.stream().map(Path::getParent).min(Comparator.comparingInt(Path::getNameCount)).orElseThrow());
        LOG.log(Level.INFO, "Compression with preserve " + root);
        addon.fInfo.stream().map(Path::getParent).map(adds::resolve).forEach(path -> {
            if (!path.equals(root)) {
                LOG.log(Level.WARNING, "DELETING " + path);
                logAppender.log("DELETING: " + adds.relativize(path));
                deleteDirectory(path);
            }
        });
    }

    public void delete(Addon addon, LogAppender logAppender) {
        final Path adds = Paths.get(addonFolder);
        addon.fInfo.stream().map(Path::getParent).map(adds::resolve).forEach(path -> {
            LOG.log(Level.WARNING, "DELETING " + path);
            logAppender.log("DELETING: " + adds.relativize(path));
            deleteDirectory(path);
        });
    }

    private Boolean deleteDirectory(Path path) {
        try {
            return Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .allMatch(File::delete);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, e, () -> "Unable to delete " + path.toString());
            return false;
        }
    }
}
