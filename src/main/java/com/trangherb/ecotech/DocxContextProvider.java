package com.trangherb.ecotech;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DocxContextProvider {
    private static final int MAX_PARAGRAPHS = 3;
    private static final int MIN_TERM_LENGTH = 3;
    private static final int FALLBACK_PARAGRAPHS = 2;
    private static final int MAX_CONTEXT_CHARS = 2000;
    private static final int MAX_ANSWER_CHARS = 600;
    private static final Pattern PACKAGE_HEADING_PATTERN = Pattern.compile("^(\\d+)\\s+goi\\s+.+");
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile("^\\s*(Gói|Goi)\\s*(\\d+)\\s*:", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DIGIT_PATTERN = Pattern.compile(".*\\d.*");

    private final List<String> paragraphs;
    private final List<PackageInfo> packages;

    public DocxContextProvider(String docxPaths) {
        this.paragraphs = loadParagraphs(docxPaths);
        this.packages = parsePackages(this.paragraphs);
    }

    public String getContextForQuery(String query) {
        if (paragraphs.isEmpty()) {
            return "";
        }

        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return fallbackContext();
        }

        List<ScoredParagraph> scored = new ArrayList<>();
        for (String paragraph : paragraphs) {
            int score = scoreParagraph(paragraph, terms);
            if (score > 0) {
                scored.add(new ScoredParagraph(paragraph, score));
            }
        }

        if (scored.isEmpty()) {
            return fallbackContext();
        }

        scored.sort(Comparator.comparingInt(ScoredParagraph::score).reversed());
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(MAX_PARAGRAPHS, scored.size());
        for (int i = 0; i < limit; i++) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(scored.get(i).paragraph());
        }
        return trimContext(builder.toString().trim());
    }

    public String getDirectAnswerForQuery(String query) {
        if (query == null || query.isBlank() || packages.isEmpty()) {
            return "";
        }

        String normalizedQuery = normalize(query);
        Integer packageId = extractPackageId(normalizedQuery);
        boolean priceIntent = isPriceIntent(normalizedQuery);
        boolean listIntent = isListIntent(normalizedQuery);

        if (listIntent && packageId == null && !priceIntent) {
            return formatPackageList();
        }

        if (priceIntent) {
            if (packageId != null) {
                return formatPackagePrice(packageId);
            }
            if (listIntent || normalizedQuery.contains("goi")) {
                return formatAllPrices();
            }
        }

        if (packageId != null) {
            return formatPackageDetail(packageId);
        }

        return "";
    }

    public String getAnswerForQuery(String query) {
        String direct = getDirectAnswerForQuery(query);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }

        if (paragraphs.isEmpty()) {
            return "";
        }

        String normalizedQuery = normalize(query == null ? "" : query);
        List<String> scored = topParagraphs(normalizedQuery);
        if (scored.isEmpty()) {
            return "";
        }

        String answer;
        if (normalizedQuery.contains("san pham") || normalizedQuery.contains("dich vu")) {
            answer = extractProducts(scored);
        } else if (normalizedQuery.contains("tra hoa trang") || (normalizedQuery.contains("hoa trang") && normalizedQuery.contains("tra"))) {
            answer = extractByKeywords(scored, List.of("hoa trang", "duoc lieu", "tra"));
        } else if (normalizedQuery.contains("la gi") || (normalizedQuery.contains("trangherb") && normalizedQuery.contains("la"))) {
            answer = extractDefinition(scored);
        } else {
            answer = extractByKeywords(scored, List.of());
        }

        if (answer.isBlank()) {
            answer = firstSentences(scored.get(0), 2);
        }

        return trimAnswer(cleanHeading(answer));
    }

    private List<String> loadParagraphs(String docxPaths) {
        String rawPaths = docxPaths == null ? "" : docxPaths.trim();
        if (rawPaths.isEmpty()) {
            return List.of();
        }
        String[] pathValues = rawPaths.split(";");
        List<String> all = new ArrayList<>();

        for (String pathValue : pathValues) {
            String trimmed = pathValue == null ? "" : pathValue.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed).toAbsolutePath();
            if (!Files.exists(path)) {
                continue;
            }
            all.addAll(loadParagraphsFromDocx(path));
        }

        return all;
    }

    private List<String> loadParagraphsFromDocx(Path path) {
        try (ZipFile zipFile = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipFile.getEntry("word/document.xml");
            if (entry == null) {
                return List.of();
            }

            try (InputStream stream = zipFile.getInputStream(entry)) {
                String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return extractParagraphs(xml);
            }
        } catch (IOException ex) {
            return List.of();
        }
    }

    private List<String> extractParagraphs(String xml) {
        List<String> results = new ArrayList<>();
        Pattern paragraphPattern = Pattern.compile("<w:p[^>]*>(.*?)</w:p>", Pattern.DOTALL);
        Matcher paragraphMatcher = paragraphPattern.matcher(xml);
        Pattern textPattern = Pattern.compile("<w:t[^>]*>(.*?)</w:t>", Pattern.DOTALL);

        while (paragraphMatcher.find()) {
            String paragraphXml = paragraphMatcher.group(1);
            Matcher textMatcher = textPattern.matcher(paragraphXml);
            StringBuilder builder = new StringBuilder();
            while (textMatcher.find()) {
                builder.append(decodeXml(textMatcher.group(1)));
            }
            String paragraph = normalizeWhitespace(builder.toString());
            if (!paragraph.isBlank()) {
                results.add(paragraph);
            }
        }

        return results;
    }

    private String decodeXml(String value) {
        return value.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private List<String> tokenize(String value) {
        if (value == null) {
            return List.of();
        }
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] raw = normalized.split(" ");
        List<String> terms = new ArrayList<>();
        for (String token : raw) {
            if (token.length() >= MIN_TERM_LENGTH) {
                terms.add(token);
            }
        }
        return terms;
    }

    private int scoreParagraph(String paragraph, List<String> terms) {
        String normalized = normalize(paragraph);
        int score = 0;
        for (String term : terms) {
            if (normalized.contains(term)) {
                score += 1;
            }
        }
        return score;
    }

    private String fallbackContext() {
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(FALLBACK_PARAGRAPHS, paragraphs.size());
        for (int i = 0; i < limit; i++) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(paragraphs.get(i));
        }
        return trimContext(builder.toString().trim());
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private String trimContext(String context) {
        if (context.length() <= MAX_CONTEXT_CHARS) {
            return context;
        }
        return context.substring(0, MAX_CONTEXT_CHARS).trim();
    }

    private String trimAnswer(String answer) {
        if (answer.length() <= MAX_ANSWER_CHARS) {
            return answer.trim();
        }
        return answer.substring(0, MAX_ANSWER_CHARS).trim();
    }

    private List<String> topParagraphs(String normalizedQuery) {
        List<String> terms = tokenize(normalizedQuery);
        List<ScoredParagraph> scored = new ArrayList<>();
        for (String paragraph : paragraphs) {
            int score = terms.isEmpty() ? 0 : scoreParagraph(paragraph, terms);
            if (score > 0 || terms.isEmpty()) {
                scored.add(new ScoredParagraph(paragraph, score));
            }
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        scored.sort(Comparator.comparingInt(ScoredParagraph::score).reversed());
        List<String> result = new ArrayList<>();
        int limit = Math.min(MAX_PARAGRAPHS, scored.size());
        for (int i = 0; i < limit; i++) {
            result.add(scored.get(i).paragraph());
        }
        return result;
    }

    private String extractProducts(List<String> paragraphs) {
        return formatPackageList();
    }

    private String extractByKeywords(List<String> paragraphs, List<String> keywords) {
        for (String paragraph : paragraphs) {
            if (keywords.isEmpty()) {
                String sentence = firstSentences(paragraph, 2);
                if (!sentence.isBlank()) {
                    return sentence;
                }
                continue;
            }
            String normalized = normalize(paragraph);
            boolean hit = false;
            for (String keyword : keywords) {
                if (normalized.contains(keyword)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                String sentence = firstSentences(paragraph, 2);
                if (!sentence.isBlank()) {
                    return sentence;
                }
            }
        }
        return "";
    }

    private String extractDefinition(List<String> paragraphs) {
        for (String paragraph : paragraphs) {
            String normalized = normalize(paragraph);
            if (normalized.contains("he sinh thai") && normalized.contains("tuan hoan")) {
                String sentence = firstSentences(paragraph, 2);
                if (!sentence.isBlank()) {
                    if (!sentence.toLowerCase(Locale.ROOT).contains("trangherb")) {
                        return "TrangHerb EcoTech là " + sentence;
                    }
                    return sentence;
                }
            }
        }
        return firstSentences(paragraphs.get(0), 2);
    }

    private String firstSentences(String paragraph, int count) {
        String cleaned = normalizeWhitespace(paragraph);
        String[] parts = cleaned.split("(?<=[.!?…])\\s+");
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(trimmed);
            added += 1;
            if (added >= count) {
                break;
            }
        }
        return builder.toString().trim();
    }

    private String cleanHeading(String text) {
        String cleaned = text.replaceAll("^\\s*\\d+\\.?\\s*", "").trim();
        return cleaned.replace("TrangHerb EcoTech – Hành Trình Xanh Cộng Hưởng Với Sức Khỏe Cộng Đồng & Nông Nghiệp Tuần Hoàn", "TrangHerb EcoTech");
    }

    private boolean isListIntent(String normalizedQuery) {
        return normalizedQuery.contains("san pham")
                || normalizedQuery.contains("dich vu")
                || normalizedQuery.contains("goi dich vu")
                || normalizedQuery.contains("co goi")
                || normalizedQuery.contains("co san pham")
                || normalizedQuery.contains("cac goi");
    }

    private boolean isPriceIntent(String normalizedQuery) {
        return normalizedQuery.contains("bao nhieu")
                || normalizedQuery.contains("gia ca")
                || normalizedQuery.contains("muc gia")
                || normalizedQuery.contains("chi phi")
                || normalizedQuery.contains("gia")
                || (normalizedQuery.contains("phi") && normalizedQuery.contains("goi"));
    }

    private Integer extractPackageId(String normalizedQuery) {
        Matcher matcher = Pattern.compile("goi\\s*(?:so|thu)?\\s*(\\d+)").matcher(normalizedQuery);
        if (matcher.find()) {
            int value = safeParseInt(matcher.group(1));
            return value > 0 ? value : null;
        }
        if (normalizedQuery.contains("goi mot") || normalizedQuery.contains("goi so mot") || normalizedQuery.contains("goi thu mot")) {
            return 1;
        }
        if (normalizedQuery.contains("goi hai") || normalizedQuery.contains("goi so hai") || normalizedQuery.contains("goi thu hai")) {
            return 2;
        }
        if (normalizedQuery.contains("goi ba") || normalizedQuery.contains("goi so ba") || normalizedQuery.contains("goi thu ba")) {
            return 3;
        }
        return null;
    }

    private String formatPackageList() {
        if (packages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Dạ bên mình có các gói dịch vụ sau:");
        for (PackageInfo info : packages) {
            String line = "Gói " + info.id() + " - " + info.name();
            String price = formatPrice(info.price());
            if (!price.isBlank()) {
                line = line + " (" + price + ")";
            }
            builder.append("\n- ").append(line);
        }
        return builder.toString().trim();
    }

    private String formatAllPrices() {
        if (packages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Dạ, mức giá các gói hiện có:");
        for (PackageInfo info : packages) {
            String price = formatPrice(info.price());
            if (price.isBlank()) {
                price = "chưa có thông tin giá";
            }
            builder.append("\n- Gói ")
                    .append(info.id())
                    .append(" - ")
                    .append(info.name())
                    .append(": ")
                    .append(price);
        }
        return builder.toString().trim();
    }

    private String formatPackagePrice(int packageId) {
        PackageInfo info = findPackage(packageId);
        if (info == null) {
            return "Dạ, hiện tài liệu chưa có thông tin giá cho gói " + packageId + ".";
        }
        String price = formatPrice(info.price());
        if (price.isBlank()) {
            return "Dạ, hiện tài liệu chưa có thông tin giá cụ thể cho Gói " + packageId + ".";
        }
        return "Dạ, Gói " + packageId + " - " + info.name() + " có mức giá " + price + ".";
    }

    private String formatPackageDetail(int packageId) {
        PackageInfo info = findPackage(packageId);
        if (info == null) {
            return "Dạ, hiện tài liệu chưa có thông tin về Gói " + packageId + ".";
        }
        StringBuilder builder = new StringBuilder("Dạ, Gói " + packageId + " - " + info.name() + " gồm:");
        List<String> lines = splitDescriptionLines(info.description());
        if (lines.isEmpty()) {
            builder.append("\n- Hiện tài liệu chưa mô tả chi tiết cho gói này.");
        } else {
            int limit = Math.min(4, lines.size());
            for (int i = 0; i < limit; i++) {
                builder.append("\n- ").append(lines.get(i));
            }
        }
        String price = formatPrice(info.price());
        if (!price.isBlank()) {
            builder.append("\nMức giá: ").append(price).append(".");
        }
        return builder.toString().trim();
    }

    private List<String> splitDescriptionLines(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        String[] raw = description.split("\\n+");
        List<String> lines = new ArrayList<>();
        for (String item : raw) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private PackageInfo findPackage(int packageId) {
        for (PackageInfo info : packages) {
            if (info.id() == packageId) {
                return info;
            }
        }
        return null;
    }

    private String formatPrice(String price) {
        if (price == null) {
            return "";
        }
        String trimmed = normalizeWhitespace(price);
        if (trimmed.isBlank()) {
            return "";
        }
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        if (lowered.contains("vnđ") || lowered.contains("vnd")) {
            return trimmed;
        }
        if (!DIGIT_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (trimmed.matches("^[0-9.]+$")) {
            return trimmed + " VNĐ";
        }
        if (trimmed.matches("^[0-9.]+\\s*\\(.*\\)$")) {
            return trimmed.replaceFirst("^([0-9.]+)\\s*\\(", "$1 VNĐ (");
        }
        return trimmed;
    }

    private List<PackageInfo> parsePackages(List<String> paragraphs) {
        if (paragraphs.isEmpty()) {
            return List.of();
        }

        List<PackageHeading> headings = new ArrayList<>();
        Map<Integer, String> rowNames = new HashMap<>();
        Map<Integer, String> priceMap = new HashMap<>();

        for (int i = 0; i < paragraphs.size(); i++) {
            String paragraph = paragraphs.get(i);
            String normalized = normalize(paragraph);

            Matcher headingMatcher = PACKAGE_HEADING_PATTERN.matcher(normalized);
            if (headingMatcher.find()) {
                int id = safeParseInt(headingMatcher.group(1));
                if (id > 0) {
                    String name = stripLeadingPackageLabel(paragraph);
                    headings.add(new PackageHeading(id, i, name));
                }
            }

            Matcher rowMatcher = TABLE_ROW_PATTERN.matcher(paragraph);
            if (rowMatcher.find()) {
                int id = safeParseInt(rowMatcher.group(2));
                if (id > 0) {
                    String rowName = stripTableRowLabel(paragraph);
                    if (!rowName.isBlank()) {
                        rowNames.putIfAbsent(id, rowName);
                    }
                    String price = findNextPrice(paragraphs, i + 1);
                    if (!price.isBlank()) {
                        priceMap.putIfAbsent(id, price);
                    }
                }
            }
        }

        Map<Integer, String> descMap = new HashMap<>();
        for (int i = 0; i < headings.size(); i++) {
            PackageHeading heading = headings.get(i);
            int start = heading.index() + 1;
            int end = (i + 1 < headings.size()) ? headings.get(i + 1).index() : paragraphs.size();
            List<String> parts = new ArrayList<>();
            for (int j = start; j < end; j++) {
                String paragraph = paragraphs.get(j);
                if (isTableHeader(paragraph) || isTableRow(paragraph)) {
                    break;
                }
                if (!paragraph.isBlank()) {
                    parts.add(paragraph);
                }
            }
            if (!parts.isEmpty()) {
                descMap.put(heading.id(), String.join("\n", parts));
            }
        }

        Map<Integer, String> nameMap = new HashMap<>();
        for (PackageHeading heading : headings) {
            if (heading.name() != null && !heading.name().isBlank()) {
                nameMap.put(heading.id(), heading.name());
            }
        }
        for (Map.Entry<Integer, String> entry : rowNames.entrySet()) {
            nameMap.putIfAbsent(entry.getKey(), entry.getValue());
        }

        Set<Integer> ids = new HashSet<>();
        ids.addAll(nameMap.keySet());
        ids.addAll(descMap.keySet());
        ids.addAll(priceMap.keySet());

        List<PackageInfo> result = new ArrayList<>();
        for (Integer id : ids) {
            String name = nameMap.getOrDefault(id, "Gói " + id);
            String description = descMap.getOrDefault(id, "");
            String price = priceMap.getOrDefault(id, "");
            result.add(new PackageInfo(id, name, description, price));
        }
        result.sort(Comparator.comparingInt(PackageInfo::id));
        return result;
    }

    private boolean isTableHeader(String paragraph) {
        String normalized = normalize(paragraph);
        return normalized.startsWith("ten goi dich vu")
                || normalized.startsWith("hoat dong chinh")
                || normalized.startsWith("muc gia");
    }

    private boolean isTableRow(String paragraph) {
        return paragraph != null && TABLE_ROW_PATTERN.matcher(paragraph).find();
    }

    private String findNextPrice(List<String> paragraphs, int startIndex) {
        int limit = Math.min(paragraphs.size(), startIndex + 3);
        for (int i = startIndex; i < limit; i++) {
            String paragraph = paragraphs.get(i);
            if (isTableHeader(paragraph) || isTableRow(paragraph)) {
                break;
            }
            if (isPriceValue(paragraph)) {
                return paragraph.trim();
            }
        }
        return "";
    }

    private boolean isPriceValue(String paragraph) {
        if (paragraph == null || paragraph.isBlank()) {
            return false;
        }
        return DIGIT_PATTERN.matcher(paragraph).matches();
    }

    private String stripLeadingPackageLabel(String paragraph) {
        return paragraph.replaceFirst("^\\s*\\d+\\.?\\s*(Gói|Goi)\\s+", "").trim();
    }

    private String stripTableRowLabel(String paragraph) {
        return paragraph.replaceFirst("^\\s*(Gói|Goi)\\s*\\d+\\s*:\\s*", "").trim();
    }

    private int safeParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return -1;
        }
    }

    private record ScoredParagraph(String paragraph, int score) {}

    private record PackageHeading(int id, int index, String name) {}

    private record PackageInfo(int id, String name, String description, String price) {}
}

