package com.lostfound.matching;

import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Deterministic, explainable lost/found similarity.
 *
 * <p>The score is a <em>similarity score</em>, never an ownership probability
 * and never a reason to approve a claim. It is computed locally so the demo
 * needs no model download, API key, or network access.</p>
 *
 * <p>Formula (score rounded to an integer 0-100):</p>
 * <pre>
 *   60% text similarity   weighted title/description cosine
 *   20% exact category    normalized category equality
 *   15% location          containment overlap of normalized location tokens
 *    5% date proximity    1 - daysApart / 14, zero beyond fourteen days
 * </pre>
 *
 * <p>Text similarity uses term-frequency cosine. Title tokens count three
 * times as much as description tokens, which keeps a short descriptive title
 * from being buried by a long description. Input is lower-cased, punctuation
 * and stop words are removed, and simple plural endings are folded so
 * "keys"/"key" overlap. Text similarity must reach {@value #MIN_TEXT} before a
 * pair is eligible, so category/date/location alone can never create a match.
 * Only OPEN, non-deleted reports from two different reporters of opposite
 * types are compared. Private ownership questions, answers, contacts, OTPs and
 * photo metadata never enter a profile.</p>
 *
 * <p>Positive reasons are emitted only for signals that actually contributed:
 * a similar description, shared distinctive terms, an exact category, an
 * overlapping location, and a date inside the fourteen-day window.</p>
 */
@Component
public class MatchScoring {

    public static final double TEXT_WEIGHT = 0.60;
    public static final double CATEGORY_WEIGHT = 0.20;
    public static final double LOCATION_WEIGHT = 0.15;
    public static final double DATE_WEIGHT = 0.05;

    /** A pair must have at least this much text overlap to be eligible. */
    public static final double MIN_TEXT = 0.20;
    /** The UI and snapshot store candidates at or above this score. */
    public static final int MIN_SCORE = 35;
    /** Alerts (owned by another feature) only consume at or above this score. */
    public static final int ALERT_SCORE = 60;

    private static final int DATE_WINDOW_DAYS = 14;
    private static final double TITLE_TOKEN_WEIGHT = 3.0;
    private static final double DESCRIPTION_TOKEN_WEIGHT = 1.0;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "with", "without", "for", "from",
            "in", "on", "at", "to", "of", "is", "was", "were", "be", "been", "being",
            "it", "its", "this", "that", "these", "those", "i", "my", "me", "we",
            "our", "you", "your", "he", "she", "they", "them", "his", "her", "their",
            "as", "by", "near", "about", "after", "before", "during", "into", "over",
            "under", "up", "down", "out", "off", "then", "than", "so", "if", "not",
            "no", "yes", "have", "has", "had", "do", "does", "did", "can", "could",
            "would", "should", "will", "just", "very", "really", "also", "there",
            "here", "when", "where", "what", "which", "who", "whom", "how", "lost",
            "found", "missing", "item", "items", "someone", "something", "please",
            "think", "maybe", "around", "some", "any", "all", "one", "two",
            // Product/model noise must not create a suggestion on its own.
            "pro", "max", "plus", "mini", "lite", "series", "edition", "model");

    /** A report's matching view, computed once per refresh and reused for every pair. */
    public record MatchProfile(
            Item item,
            String normalizedCategory,
            Set<String> locationTokens,
            LocalDate occurredOn,
            Map<String, Double> textWeights,
            double textNorm,
            Set<String> distinctTerms
    ) {
    }

    public record MatchScore(
            int score,
            double textSimilarity,
            boolean categoryMatch,
            double locationSimilarity,
            double dateSimilarity,
            long daysApart,
            List<String> reasons
    ) {
    }

    public MatchProfile profile(Item item) {
        Map<String, Double> weights = new HashMap<>();
        addTokens(weights, item.getTitle(), TITLE_TOKEN_WEIGHT);
        addTokens(weights, item.getDescription(), DESCRIPTION_TOKEN_WEIGHT);
        double norm = Math.sqrt(weights.values().stream()
                .mapToDouble(weight -> weight * weight)
                .sum());
        return new MatchProfile(
                item,
                normalizeCategory(item.getCategory()),
                locationTokens(item.getLocation()),
                item.getDateOccurred().atZone(ZoneOffset.UTC).toLocalDate(),
                Map.copyOf(weights),
                norm,
                Set.copyOf(weights.keySet()));
    }

    /**
     * Scores one canonical LOST/FOUND pair.
     *
     * @return the score when the pair is eligible, otherwise empty. Callers must
     *         still enforce report ownership/status rules (this method does too).
     */
    public Optional<MatchScore> score(MatchProfile lost, MatchProfile found) {
        Item lostItem = lost.item();
        Item foundItem = found.item();
        if (lostItem.getType() != ItemType.LOST || foundItem.getType() != ItemType.FOUND) {
            return Optional.empty();
        }
        if (!eligible(lostItem) || !eligible(foundItem)) {
            return Optional.empty();
        }
        if (Objects.equals(lostItem.getReporter().getId(), foundItem.getReporter().getId())) {
            return Optional.empty();
        }

        double text = cosine(lost, found);
        if (text < MIN_TEXT) {
            return Optional.empty();
        }

        boolean categoryMatch = lost.normalizedCategory() != null
                && lost.normalizedCategory().equals(found.normalizedCategory());
        double location = locationSimilarity(lost.locationTokens(), found.locationTokens());
        long daysApart = Math.abs(ChronoUnit.DAYS.between(lost.occurredOn(), found.occurredOn()));
        double date = daysApart > DATE_WINDOW_DAYS
                ? 0.0
                : 1.0 - ((double) daysApart / DATE_WINDOW_DAYS);

        double raw = TEXT_WEIGHT * text
                + CATEGORY_WEIGHT * (categoryMatch ? 1.0 : 0.0)
                + LOCATION_WEIGHT * location
                + DATE_WEIGHT * date;
        int score = (int) Math.max(0, Math.min(100, Math.round(raw * 100)));
        if (score < MIN_SCORE) {
            return Optional.empty();
        }

        List<String> reasons = new ArrayList<>();
        reasons.add("Similar item description");
        List<String> shared = sharedTerms(lost, found);
        if (!shared.isEmpty()) {
            reasons.add("Shared details: " + String.join(", ", shared));
        }
        if (categoryMatch) {
            reasons.add("Same category: " + lostItem.getCategory());
        }
        if (location >= 0.999) {
            reasons.add("Same campus location: " + lostItem.getLocation());
        } else if (location >= 0.5) {
            reasons.add("Nearby location: " + lostItem.getLocation());
        }
        if (daysApart == 0) {
            reasons.add("Same date");
        } else if (daysApart < DATE_WINDOW_DAYS) {
            reasons.add(daysApart == 1
                    ? "Dates about a day apart"
                    : "Dates " + daysApart + " days apart");
        }

        return Optional.of(new MatchScore(score, text, categoryMatch, location, date,
                daysApart, List.copyOf(reasons)));
    }

    private boolean eligible(Item item) {
        return item.getStatus() == ItemStatus.OPEN && !item.isDeleted();
    }

    private double cosine(MatchProfile left, MatchProfile right) {
        if (left.textNorm() == 0 || right.textNorm() == 0) {
            return 0;
        }
        Map<String, Double> smaller = left.textWeights();
        Map<String, Double> larger = right.textWeights();
        if (smaller.size() > larger.size()) {
            Map<String, Double> swap = smaller;
            smaller = larger;
            larger = swap;
        }
        double dot = 0;
        for (Map.Entry<String, Double> entry : smaller.entrySet()) {
            Double other = larger.get(entry.getKey());
            if (other != null) {
                dot += entry.getValue() * other;
            }
        }
        return Math.max(0, Math.min(1, dot / (left.textNorm() * right.textNorm())));
    }

    private List<String> sharedTerms(MatchProfile lost, MatchProfile found) {
        Set<String> common = new HashSet<>(lost.distinctTerms());
        common.retainAll(found.distinctTerms());
        common.removeIf(term -> term.length() < 3);
        return common.stream()
                .sorted(Comparator
                        .comparingDouble((String term) -> -combinedWeight(lost, found, term))
                        .thenComparing(Comparator.naturalOrder()))
                .limit(3)
                .toList();
    }

    private double combinedWeight(MatchProfile lost, MatchProfile found, String term) {
        double left = lost.textWeights().getOrDefault(term, 0.0);
        double right = found.textWeights().getOrDefault(term, 0.0);
        return left + right;
    }

    private double locationSimilarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        if (left.equals(right)) {
            return 1;
        }
        Set<String> common = new HashSet<>(left);
        common.retainAll(right);
        if (common.isEmpty()) {
            return 0;
        }
        return (double) common.size() / Math.min(left.size(), right.size());
    }

    private String normalizeCategory(String category) {
        if (category == null) {
            return null;
        }
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private Set<String> locationTokens(String location) {
        if (location == null || location.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new HashSet<>();
        for (String token : tokenize(location)) {
            tokens.add(token);
        }
        return Set.copyOf(tokens);
    }

    private void addTokens(Map<String, Double> weights, String text, double weight) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String token : tokenize(text)) {
            weights.merge(token, weight, Double::sum);
        }
    }

    static List<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : normalized.split("\\s+")) {
            String token = foldPlural(raw);
            if (token.length() < 2 || STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static String foldPlural(String token) {
        if (token.length() > 3 && token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }
}
