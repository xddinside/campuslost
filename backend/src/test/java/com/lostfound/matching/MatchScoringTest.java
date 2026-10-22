package com.lostfound.matching;

import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import com.lostfound.model.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MatchScoringTest {

    private final MatchScoring scoring = new MatchScoring();

    @Test
    void strongParaphraseProducesHighExplainableScore() {
        Item lost = item(1L, ItemType.LOST, 10L,
                "Black Hydro Flask water bottle",
                "Dark blue insulated bottle covered in stickers, left on a library desk",
                "Other", "Central Library", 0);
        Item found = item(2L, ItemType.FOUND, 20L,
                "Dark blue Hydro Flask bottle found",
                "Insulated water bottle with stickers found at a desk in the library",
                "Other", "Central Library", 0);

        Optional<MatchScoring.MatchScore> result = scoring.score(scoring.profile(lost), scoring.profile(found));

        assertThat(result).isPresent();
        assertThat(result.get().score()).isGreaterThanOrEqualTo(70);
        assertThat(result.get().textSimilarity()).isGreaterThanOrEqualTo(MatchScoring.MIN_TEXT);
        assertThat(result.get().reasons())
                .contains("Similar item description",
                        "Same category: Other",
                        "Same campus location: Central Library",
                        "Same date");
        assertThat(result.get().reasons().get(0)).isEqualTo("Similar item description");
    }

    @Test
    void locationAndDateOnlyLowerTheScoreButNeverCreateAMatch() {
        Item lost = item(1L, ItemType.LOST, 10L,
                "Silver necklace moon pendant",
                "Sentimental necklace lost near the dorm walk",
                "Jewelry", "Campus Walk", 0);
        Item found = item(2L, ItemType.FOUND, 20L,
                "Silver necklace moon pendant",
                "Sentimental necklace found near the dorm walk",
                "Jewelry", "Campus Walk", 20);

        MatchScoring.MatchScore nearby = scoring.score(scoring.profile(lost), scoring.profile(found)).orElseThrow();
        assertThat(nearby.dateSimilarity()).isZero();
        assertThat(nearby.reasons()).doesNotContain("Same date");
        assertThat(nearby.reasons()).noneMatch(reason -> reason.startsWith("Dates "));
        assertThat(nearby.score()).isLessThan(100);

        Item closeInTime = item(4L, ItemType.FOUND, 40L,
                "Silver necklace moon pendant",
                "Sentimental necklace found near the dorm walk",
                "Jewelry", "Campus Walk", 2);
        MatchScoring.MatchScore withinWindow = scoring.score(scoring.profile(lost),
                scoring.profile(closeInTime)).orElseThrow();
        assertThat(withinWindow.reasons()).contains("Dates 2 days apart");
        assertThat(withinWindow.score()).isGreaterThan(nearby.score());

        Item unrelated = item(3L, ItemType.FOUND, 30L,
                "Grey wool scarf",
                "Warm scarf left on a chair",
                "Jewelry", "Campus Walk", 0);
        assertThat(scoring.score(scoring.profile(lost), scoring.profile(unrelated))).isEmpty();
    }

    @Test
    void unrelatedSameCategoryPairIsRejectedByTextGate() {
        Item lost = item(1L, ItemType.LOST, 10L,
                "Organic chemistry textbook",
                "Third edition, name written inside, left in the study room",
                "Books", "Central Library", 2);
        Item found = item(2L, ItemType.FOUND, 20L,
                "Blue rain jacket",
                "Waterproof shell found hanging by the entrance",
                "Books", "Central Library", 2);

        assertThat(scoring.score(scoring.profile(lost), scoring.profile(found))).isEmpty();
    }

    @Test
    void eligibleTextWithWeakOtherSignalsStaysBelowTheScoreThreshold() {
        Item lost = item(1L, ItemType.LOST, 10L,
                "Alpha beta gamma delta",
                "",
                "Other", "Central Library", 0);
        Item found = item(2L, ItemType.FOUND, 20L,
                "Alpha epsilon zeta eta",
                "",
                "Electronics", "University Gym", 30);

        assertThat(scoring.score(scoring.profile(lost), scoring.profile(found))).isEmpty();
    }

    @Test
    void sameReporterClosedDeletedAndSameTypeReportsAreNeverEligible() {
        Item lost = item(1L, ItemType.LOST, 10L,
                "Blue bottle", "Insulated bottle with stickers", "Other", "Library", 0);
        Item sameReporter = item(2L, ItemType.FOUND, 10L,
                "Blue bottle", "Insulated bottle with stickers", "Other", "Library", 0);
        Item reserved = item(3L, ItemType.FOUND, 20L,
                "Blue bottle", "Insulated bottle with stickers", "Other", "Library", 0);
        reserved.setStatus(ItemStatus.RESERVED);
        Item deleted = item(4L, ItemType.FOUND, 20L,
                "Blue bottle", "Insulated bottle with stickers", "Other", "Library", 0);
        deleted.setDeletedAt(Instant.now());
        Item anotherLost = item(5L, ItemType.LOST, 20L,
                "Blue bottle", "Insulated bottle with stickers", "Other", "Library", 0);

        assertThat(scoring.score(scoring.profile(lost), scoring.profile(sameReporter))).isEmpty();
        assertThat(scoring.score(scoring.profile(lost), scoring.profile(reserved))).isEmpty();
        assertThat(scoring.score(scoring.profile(lost), scoring.profile(deleted))).isEmpty();
        assertThat(scoring.score(scoring.profile(lost), scoring.profile(anotherLost))).isEmpty();
    }

    @Test
    void privateOwnershipQuestionCannotAlterTheScore() {
        Item lost = item(1L, ItemType.LOST, 10L,
                "Black backpack", "Canvas backpack with a laptop sleeve", "Other", "Library", 0);
        Item found = item(2L, ItemType.FOUND, 20L,
                "Black canvas backpack", "Backpack with a laptop sleeve", "Other", "Library", 0);
        Item foundWithQuestion = item(3L, ItemType.FOUND, 20L,
                "Black canvas backpack", "Backpack with a laptop sleeve", "Other", "Library", 0);
        foundWithQuestion.setOwnershipQuestion("What is scribbled on the secret inside label?");

        int plain = scoring.score(scoring.profile(lost), scoring.profile(found)).orElseThrow().score();
        int withQuestion = scoring.score(scoring.profile(lost), scoring.profile(foundWithQuestion))
                .orElseThrow().score();

        assertThat(withQuestion).isEqualTo(plain);
    }

    @Test
    void repeatedTokensDoNotChangeCosineDirection() {
        Item lost = item(1L, ItemType.LOST, 10L,
                "bottle bottle bottle", "", "Other", "Gym", 0);
        Item found = item(2L, ItemType.FOUND, 20L,
                "bottle", "", "Other", "Gym", 0);

        MatchScoring.MatchScore result = scoring.score(scoring.profile(lost), scoring.profile(found)).orElseThrow();
        assertThat(result.textSimilarity()).isEqualTo(1.0);
        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void titleCarriesMoreWeightThanDescription() {
        Item titleOverlap = item(1L, ItemType.LOST, 10L,
                "Blue Hydro Flask bottle", "", "Other", "Gym", 0);
        Item foundTitle = item(2L, ItemType.FOUND, 20L,
                "Blue Hydro Flask bottle", "unrelated words here", "Other", "Gym", 0);
        Item descriptionOverlap = item(3L, ItemType.FOUND, 20L,
                "unrelated words here", "Blue Hydro Flask bottle", "Other", "Gym", 0);

        double titleScore = scoring.score(scoring.profile(titleOverlap), scoring.profile(foundTitle))
                .orElseThrow().textSimilarity();
        double descriptionScore = scoring.score(scoring.profile(titleOverlap),
                scoring.profile(descriptionOverlap)).orElseThrow().textSimilarity();

        assertThat(titleScore).isGreaterThan(descriptionScore);
    }

    private Item item(long id, ItemType type, long reporterId, String title, String description,
                      String category, String location, int daysAgo) {
        User reporter = new User();
        reporter.setId(reporterId);
        reporter.setName("Reporter " + reporterId);
        reporter.setEmail("reporter" + reporterId + "@test.dev");
        reporter.setPasswordHash("hash");

        Item item = new Item();
        item.setId(id);
        item.setType(type);
        item.setReporter(reporter);
        item.setTitle(title);
        item.setDescription(description);
        item.setCategory(category);
        item.setLocation(location);
        item.setDateOccurred(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        item.setStatus(ItemStatus.OPEN);
        return item;
    }
}
