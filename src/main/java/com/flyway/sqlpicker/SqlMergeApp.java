package com.flyway.sqlpicker;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SqlMergeApp {

    private static final Pattern MIGRATION_SEQ_PATTERN = Pattern.compile("^V(\\d+)__", Pattern.CASE_INSENSITIVE);

    private SqlMergeApp() {
    }

    public static void main(String[] args) {
        Path configPath = resolveConfigPath(args);
        try {
            MergeConfig config = MergeConfig.load(configPath);
            MergeResult result = merge(config);
            System.out.println("Success. mergedFiles=" + result.mergedFiles + ", output=" + result.outputFile.toAbsolutePath());
        } catch (Exception ex) {
            System.err.println("Failed: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static Path resolveConfigPath(String[] args) {
        if (args == null || args.length == 0) {
            return Paths.get("config.properties");
        }

        if (args.length == 2 && "-c".equals(args[0])) {
            return Paths.get(args[1]);
        }

        if (args.length == 1) {
            return Paths.get(args[0]);
        }

        throw new IllegalArgumentException("Usage: java -jar flyway-sql-picker-1.0.0.jar [-c config.properties]");
    }

    static MergeResult merge(MergeConfig config) throws IOException {
        validateConfig(config);

        List<Path> candidates = discoverSqlFiles(config);
        List<Path> selected = filterByVersion(config, candidates);
        if (selected.isEmpty()) {
            throw new IllegalStateException("No SQL files matched. Please check db.name / versions configuration.");
        }

        Path outputFile = config.outputFile.toAbsolutePath().normalize();
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
                outputFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            for (Path sqlFile : selected) {
                if (config.includeFileHeader) {
                    writer.write("-- >>> " + config.sqlRoot.relativize(sqlFile).toString().replace('\\', '/'));
                    writer.newLine();
                }
                List<String> lines = Files.readAllLines(sqlFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
                writer.newLine();
            }
        }

        return new MergeResult(outputFile, selected.size());
    }

    private static void validateConfig(MergeConfig config) {
        if (!Files.exists(config.sqlRoot) || !Files.isDirectory(config.sqlRoot)) {
            throw new IllegalArgumentException("sql.root does not exist or is not a directory: " + config.sqlRoot);
        }
        if (config.dbName == null || config.dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("db.name must not be empty");
        }
        if (config.selectionMode == SelectionMode.VERSIONS && config.versionTokens.isEmpty()) {
            throw new IllegalArgumentException("selection.mode=versions requires selection.versions");
        }
    }

    private static List<Path> discoverSqlFiles(MergeConfig config) throws IOException {
        String dbLower = config.dbName.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(config.sqlRoot)) {
            List<Path> paths = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .filter(path -> pathContainsSegment(path, dbLower))
                    .sorted(sqlFileComparator(config.sqlRoot))
                    .collect(Collectors.toList());

            if (paths.isEmpty()) {
                throw new IllegalStateException("No SQL files found for db.name=" + config.dbName + " under " + config.sqlRoot);
            }
            return paths;
        }
    }

    private static boolean pathContainsSegment(Path path, String dbLower) {
        for (Path segment : path) {
            if (dbLower.equals(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Comparator<Path> sqlFileComparator(final Path sqlRoot) {
        return new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                Path leftRelative = sqlRoot.relativize(left);
                Path rightRelative = sqlRoot.relativize(right);

                String leftModule = leftRelative.getNameCount() > 0 ? leftRelative.getName(0).toString() : "";
                String rightModule = rightRelative.getNameCount() > 0 ? rightRelative.getName(0).toString() : "";
                int moduleCompare = leftModule.compareToIgnoreCase(rightModule);
                if (moduleCompare != 0) {
                    return moduleCompare;
                }

                int leftSeq = extractMigrationSequence(left.getFileName().toString());
                int rightSeq = extractMigrationSequence(right.getFileName().toString());
                int seqCompare = Integer.compare(leftSeq, rightSeq);
                if (seqCompare != 0) {
                    return seqCompare;
                }

                return left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString());
            }
        };
    }

    private static int extractMigrationSequence(String fileName) {
        Matcher matcher = MIGRATION_SEQ_PATTERN.matcher(fileName);
        if (!matcher.find()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private static List<Path> filterByVersion(MergeConfig config, List<Path> candidates) {
        if (config.selectionMode == SelectionMode.ALL) {
            return candidates;
        }

        List<Path> selected = new ArrayList<Path>();
        for (Path file : candidates) {
            String fileNameLower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (matchesAnyVersionToken(fileNameLower, config.versionTokens)) {
                selected.add(file);
            }
        }
        return selected;
    }

    private static boolean matchesAnyVersionToken(String fileNameLower, List<String> versionTokens) {
        for (String token : versionTokens) {
            String lowerToken = token.toLowerCase(Locale.ROOT);
            if (fileNameLower.contains(lowerToken)) {
                return true;
            }
        }
        return false;
    }

    static final class MergeResult {
        private final Path outputFile;
        private final int mergedFiles;

        MergeResult(Path outputFile, int mergedFiles) {
            this.outputFile = outputFile;
            this.mergedFiles = mergedFiles;
        }
    }

    static final class MergeConfig {
        private final Path sqlRoot;
        private final String dbName;
        private final SelectionMode selectionMode;
        private final List<String> versionTokens;
        private final Path outputFile;
        private final boolean includeFileHeader;

        private MergeConfig(Path sqlRoot,
                            String dbName,
                            SelectionMode selectionMode,
                            List<String> versionTokens,
                            Path outputFile,
                            boolean includeFileHeader) {
            this.sqlRoot = sqlRoot;
            this.dbName = dbName;
            this.selectionMode = selectionMode;
            this.versionTokens = versionTokens;
            this.outputFile = outputFile;
            this.includeFileHeader = includeFileHeader;
        }

        static MergeConfig load(Path configPath) throws IOException {
            if (!Files.exists(configPath)) {
                throw new IllegalArgumentException("Config file not found: " + configPath.toAbsolutePath());
            }

            Properties properties = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }

            Path sqlRoot = normalizePath(required(properties, "sql.root"));
            String dbName = required(properties, "db.name").trim();
            SelectionMode mode = SelectionMode.from(required(properties, "selection.mode"));
            List<String> versions = parseVersions(properties.getProperty("selection.versions", ""));
            Path outputFile = normalizePath(required(properties, "output.file"));
            boolean includeHeader = Boolean.parseBoolean(properties.getProperty("output.includeFileHeader", "true"));

            return new MergeConfig(sqlRoot, dbName, mode, versions, outputFile, includeHeader);
        }

        private static String required(Properties properties, String key) {
            String value = properties.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing required config: " + key);
            }
            return value;
        }

        private static Path normalizePath(String rawPath) {
            return Paths.get(rawPath.trim()).toAbsolutePath().normalize();
        }

        private static List<String> parseVersions(String text) {
            if (text == null || text.trim().isEmpty()) {
                return Collections.emptyList();
            }
            String[] split = text.split(",");
            List<String> versions = new ArrayList<String>();
            for (String v : split) {
                String trimmed = v.trim();
                if (!trimmed.isEmpty()) {
                    versions.add(trimmed);
                }
            }
            return versions;
        }
    }

    enum SelectionMode {
        ALL,
        VERSIONS;

        static SelectionMode from(String value) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if ("ALL".equals(normalized)) {
                return ALL;
            }
            if ("VERSIONS".equals(normalized)) {
                return VERSIONS;
            }
            throw new IllegalArgumentException("selection.mode must be ALL or VERSIONS, actual=" + value);
        }
    }
}
