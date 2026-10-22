package com.lostfound.matching;

import com.lostfound.contract.MatchCandidatesChangedEvent;
import com.lostfound.model.*;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.PhotoRepository;
import com.lostfound.repository.UserRepository;
import com.lostfound.security.JwtService;
import com.lostfound.support.CleanFlywayConfig;
import com.lostfound.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({CleanFlywayConfig.class, MatchingApiTest.EventCaptureConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MatchingApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    ItemRepository items;
    @Autowired
    ClaimRepository claims;
    @Autowired
    PhotoRepository photos;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JwtService jwtService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    MatchCandidateRepository candidates;
    @Autowired
    MatchRefreshService refreshService;
    @Autowired
    EventCapture events;

    TestFixtures fixtures;
    User alice;
    User bob;
    User carol;
    User admin;

    @BeforeEach
    void setUp() {
        candidates.deleteAll();
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
        bob = fixtures.user("Bob", Role.USER);
        carol = fixtures.user("Carol", Role.USER);
        admin = fixtures.user("Admin", Role.ADMIN);
        events.clear();
    }

    @AfterEach
    void tearDown() {
        candidates.deleteAll();
    }

    @Test
    void ownerSeesExplainedSuggestionsAndOtherAudiencesAreControlled() throws Exception {
        JsonNode lost = postItem(alice, ItemType.LOST,
                "Black Hydro Flask water bottle",
                "Dark blue insulated bottle with stickers, left on a library desk",
                "Other", "Central Library", Instant.now());
        JsonNode found = postItem(bob, ItemType.FOUND,
                "Dark blue Hydro Flask bottle found",
                "Insulated water bottle with stickers found at a desk in the library",
                "Other", "Central Library", Instant.now());

        mvc.perform(get("/api/items/" + lost.get("id").asLong() + "/matches")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(lost.get("id").asLong()))
                .andExpect(jsonPath("$.matches.length()").value(1))
                .andExpect(jsonPath("$.matches[0].lostItemId").value(lost.get("id").asLong()))
                .andExpect(jsonPath("$.matches[0].foundItemId").value(found.get("id").asLong()))
                .andExpect(jsonPath("$.matches[0].item.id").value(found.get("id").asLong()))
                .andExpect(jsonPath("$.matches[0].item.title").value("Dark blue Hydro Flask bottle found"))
                .andExpect(jsonPath("$.matches[0].score").isNumber())
                .andExpect(jsonPath("$.matches[0].reasons[0]").value("Similar item description"))
                .andExpect(jsonPath("$.matches[0].item.reporterEmail").doesNotExist())
                .andExpect(jsonPath("$.matches[0].item.ownershipQuestion").doesNotExist());

        mvc.perform(get("/api/items/" + lost.get("id").asLong() + "/matches")
                        .header("Authorization", bearer(carol)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/items/" + lost.get("id").asLong() + "/matches"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/items/" + lost.get("id").asLong() + "/matches")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(1));

        mvc.perform(get("/api/items/" + found.get("id").asLong() + "/matches")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].item.id").value(lost.get("id").asLong()));
    }

    @Test
    void emptyResultIsAnHonestTwoHundred() throws Exception {
        JsonNode lonely = postItem(alice, ItemType.LOST,
                "Silver necklace moon pendant",
                "Sentimental necklace lost on the campus walk",
                "Jewelry", "Campus Walk", Instant.now());
        postItem(bob, ItemType.FOUND,
                "Organic chemistry textbook",
                "Third edition with a name written inside",
                "Books", "Central Library", Instant.now().minus(3, ChronoUnit.DAYS));

        mvc.perform(get("/api/items/" + lonely.get("id").asLong() + "/matches")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(lonely.get("id").asLong()))
                .andExpect(jsonPath("$.matches.length()").value(0));
    }

    @Test
    void ineligiblePairsNeverAppear() throws Exception {
        JsonNode lost = postItem(alice, ItemType.LOST,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());

        // Same reporter.
        postItem(alice, ItemType.FOUND,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        // Same type.
        postItem(bob, ItemType.LOST,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        // Non-open.
        JsonNode reserved = postItem(bob, ItemType.FOUND,
                "Blue Hydro Flask bottle reserved",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        mvc.perform(patch("/api/items/" + reserved.get("id").asLong() + "/status")
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk());
        // Soft-deleted.
        JsonNode deleted = postItem(carol, ItemType.FOUND,
                "Blue Hydro Flask bottle deleted",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        mvc.perform(delete("/api/items/" + deleted.get("id").asLong())
                        .header("Authorization", bearer(carol)))
                .andExpect(status().isNoContent());

        refreshService.refreshForItem(lost.get("id").asLong());

        mvc.perform(get("/api/items/" + lost.get("id").asLong() + "/matches")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(0));
    }

    @Test
    void editResolutionDeletionAndReleaseRefreshBothSides() throws Exception {
        JsonNode lost = postItem(alice, ItemType.LOST,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers, left on a desk",
                "Other", "Central Library", Instant.now());
        JsonNode found = postItem(bob, ItemType.FOUND,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers, found on a desk",
                "Other", "Central Library", Instant.now());

        assertMatches(lost.get("id").asLong(), alice, 1);
        assertMatches(found.get("id").asLong(), bob, 1);

        // An edit that removes the text signal clears both authors' lists.
        mvc.perform(patch("/api/items/" + found.get("id").asLong())
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"title":"Grey wool scarf","description":"A warm scarf left on a chair","category":"Clothing","location":"Media Lab"}
                                """))
                .andExpect(status().isOk());
        assertMatches(lost.get("id").asLong(), alice, 0);
        assertMatches(found.get("id").asLong(), bob, 0);

        // Edit back restores the pair.
        mvc.perform(patch("/api/items/" + found.get("id").asLong())
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":1,"title":"Blue Hydro Flask bottle","description":"Insulated bottle covered in stickers, found on a desk","category":"Other","location":"Central Library"}
                                """))
                .andExpect(status().isOk());
        assertMatches(lost.get("id").asLong(), alice, 1);
        assertMatches(found.get("id").asLong(), bob, 1);

        // Claim, approve, release: both reports reserve then reopen.
        MvcResult claimResult = mvc.perform(post("/api/items/" + found.get("id").asLong() + "/claims")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"That is my bottle, it has a campus sticker inside.\",\"lostReportId\":"
                                + lost.get("id").asLong()
                                + ",\"ownershipAnswer\":\"It has my name sticker inside the cap.\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long claimId = objectMapper.readTree(claimResult.getResponse().getContentAsString())
                .get("id").asLong();

        mvc.perform(patch("/api/claims/" + claimId)
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        assertMatches(lost.get("id").asLong(), alice, 0);
        assertMatches(found.get("id").asLong(), bob, 0);

        mvc.perform(post("/api/claims/" + claimId + "/release")
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Could not verify\"}"))
                .andExpect(status().isOk());
        assertMatches(lost.get("id").asLong(), alice, 1);
        assertMatches(found.get("id").asLong(), bob, 1);

        // Manual resolution hides matches; reopening restores them.
        mvc.perform(patch("/api/items/" + lost.get("id").asLong() + "/status")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk());
        assertMatches(lost.get("id").asLong(), alice, 0);
        assertMatches(found.get("id").asLong(), bob, 0);

        mvc.perform(patch("/api/items/" + lost.get("id").asLong() + "/status")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk());
        assertMatches(lost.get("id").asLong(), alice, 1);

        // Deleting one side purges the pair for both authors.
        mvc.perform(delete("/api/items/" + found.get("id").asLong())
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNoContent());
        assertMatches(lost.get("id").asLong(), alice, 0);
    }

    @Test
    void topFiveTiesAreOrderedByCandidateId() throws Exception {
        JsonNode lost = postItem(alice, ItemType.LOST,
                "Blue Hydro Flask bottle with stickers",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());

        List<Long> foundIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            JsonNode found = postItem(bob, ItemType.FOUND,
                    "Blue Hydro Flask bottle with stickers",
                    "Insulated bottle covered in stickers",
                    "Other", "Central Library", Instant.now());
            foundIds.add(found.get("id").asLong());
        }

        MvcResult result = mvc.perform(get("/api/items/" + lost.get("id").asLong() + "/matches")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(5))
                .andReturn();
        JsonNode matches = objectMapper.readTree(result.getResponse().getContentAsString()).get("matches");
        List<Long> actual = new ArrayList<>();
        for (JsonNode match : matches) {
            actual.add(match.get("item").get("id").asLong());
        }
        assertThat(actual).containsExactlyElementsOf(foundIds.subList(0, 5));
    }

    @Test
    void privateQuestionTermsCannotChangeScoresOrReasons() throws Exception {
        JsonNode lost = postItem(alice, ItemType.LOST,
                "Black canvas backpack",
                "Canvas backpack with a laptop sleeve",
                "Other", "Central Library", Instant.now());
        JsonNode plain = postItem(bob, ItemType.FOUND,
                "Black canvas backpack",
                "Canvas backpack with a laptop sleeve",
                "Other", "Central Library", Instant.now());
        JsonNode withQuestion = postItem(carol, ItemType.FOUND,
                "Black canvas backpack",
                "Canvas backpack with a laptop sleeve",
                "Other", "Central Library", Instant.now(),
                "What word is scribbled on the secret inner tag?");

        JsonNode matches = matchesFor(lost.get("id").asLong(), alice);
        int plainScore = scoreFor(matches, plain.get("id").asLong());
        int questionScore = scoreFor(matches, withQuestion.get("id").asLong());
        assertThat(plainScore).isEqualTo(questionScore);
        assertThat(matches.toString())
                .doesNotContain("scribbled")
                .doesNotContain("secret");
    }

    @Test
    void eventPayloadUsesCommittedVersionsAndCanonicalPair() throws Exception {
        JsonNode lost = postItem(alice, ItemType.LOST,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        JsonNode found = postItem(bob, ItemType.FOUND,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        events.clear();

        mvc.perform(patch("/api/items/" + found.get("id").asLong())
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"title":"Blue Hydro Flask bottle","description":"Insulated bottle covered in stickers found on a desk"}
                                """))
                .andExpect(status().isOk());

        assertThat(events.forItem(lost.get("id").asLong())).isNotEmpty();
        MatchCandidatesChangedEvent lostEvent = events.lastForItem(lost.get("id").asLong());
        Item lostItem = items.findById(lost.get("id").asLong()).orElseThrow();
        Item foundItem = items.findById(found.get("id").asLong()).orElseThrow();
        assertThat(lostEvent.itemVersion()).isEqualTo(lostItem.getVersion());
        assertThat(lostEvent.candidates()).hasSize(1);
        assertThat(lostEvent.candidates().get(0).lostItemId()).isEqualTo(lostItem.getId());
        assertThat(lostEvent.candidates().get(0).foundItemId()).isEqualTo(foundItem.getId());
        assertThat(lostEvent.candidates().get(0).lostItemVersion()).isEqualTo(lostItem.getVersion());
        assertThat(lostEvent.candidates().get(0).foundItemVersion()).isEqualTo(foundItem.getVersion());
        assertThat(lostEvent.candidates().get(0).reasons())
                .doesNotContain("scribbled")
                .doesNotContain("secret");
    }

    @Test
    void restartCatchUpRebuildsAndRepublishesUnchangedSnapshots() throws Exception {
        JsonNode lost = postItem(alice, ItemType.LOST,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        JsonNode found = postItem(bob, ItemType.FOUND,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        assertThat(candidates.findAll()).hasSize(1);

        candidates.deleteAll();
        assertThat(candidates.findAll()).isEmpty();
        events.clear();

        MatchRefreshService.RefreshSummary summary = refreshService.refreshAll();

        assertThat(summary.affectedItems()).isGreaterThanOrEqualTo(2);
        assertThat(candidates.findAll()).hasSize(1);
        assertThat(events.forItem(lost.get("id").asLong())).isNotEmpty();
        assertThat(events.lastForItem(found.get("id").asLong()).candidates()).hasSize(1);
    }

    @Test
    void concurrentEditDuringRecomputationLeavesOnlyCurrentVersions() throws Exception {
        JsonNode lost = postItem(alice, ItemType.LOST,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        JsonNode found = postItem(bob, ItemType.FOUND,
                "Blue Hydro Flask bottle",
                "Insulated bottle covered in stickers",
                "Other", "Central Library", Instant.now());
        assertMatches(lost.get("id").asLong(), alice, 1);

        BlockingMatchScoring.arm();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstEdit = pool.submit(() -> {
                mvc.perform(patch("/api/items/" + found.get("id").asLong())
                                .header("Authorization", bearer(bob))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"version":0,"description":"Insulated bottle covered in stickers found on a desk"}
                                        """))
                        .andExpect(status().isOk());
                return null;
            });
            // The first refresh holds the item locks while it is paused in scoring.
            assertThat(BlockingMatchScoring.entered.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> secondEdit = pool.submit(() -> {
                mvc.perform(patch("/api/items/" + lost.get("id").asLong())
                                .header("Authorization", bearer(alice))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"version":0,"title":"Updated Blue Hydro Flask bottle"}
                                        """))
                        .andExpect(status().isOk());
                return null;
            });
            Thread.sleep(300);
            assertThat(secondEdit.isDone()).isFalse();

            BlockingMatchScoring.release.countDown();
            firstEdit.get(10, TimeUnit.SECONDS);
            secondEdit.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            BlockingMatchScoring.reset();
        }

        Item lostDb = items.findById(lost.get("id").asLong()).orElseThrow();
        Item foundDb = items.findById(found.get("id").asLong()).orElseThrow();
        List<MatchCandidateEntity> rows = candidates.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLostItemVersion()).isEqualTo(lostDb.getVersion());
        assertThat(rows.get(0).getFoundItemVersion()).isEqualTo(foundDb.getVersion());
        assertMatches(lost.get("id").asLong(), alice, 1);
        assertMatches(found.get("id").asLong(), bob, 1);
    }

    private void assertMatches(long itemId, User viewer, int expected) throws Exception {
        mvc.perform(get("/api/items/" + itemId + "/matches")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(expected));
    }

    private JsonNode matchesFor(long itemId, User viewer) throws Exception {
        MvcResult result = mvc.perform(get("/api/items/" + itemId + "/matches")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int scoreFor(JsonNode matches, long candidateId) {
        for (JsonNode match : matches.get("matches")) {
            if (match.get("item").get("id").asLong() == candidateId) {
                return match.get("score").asInt();
            }
        }
        throw new AssertionError("No match for candidate " + candidateId);
    }

    private JsonNode postItem(User user, ItemType type, String title, String description,
                              String category, String location, Instant occurred) throws Exception {
        return postItem(user, type, title, description, category, location, occurred, null);
    }

    private JsonNode postItem(User user, ItemType type, String title, String description,
                              String category, String location, Instant occurred,
                              String ownershipQuestion) throws Exception {
        // FOUND reports now require an ownership question once the ownership
        // feature is installed; LOST reports must not carry one.
        if (ownershipQuestion == null && type == ItemType.FOUND) {
            ownershipQuestion = "What identifying detail would only the owner know?";
        }
        String question = ownershipQuestion == null
                ? ""
                : ",\"ownershipQuestion\":" + objectMapper.writeValueAsString(ownershipQuestion);
        String body = """
                {"type":"%s","title":%s,"description":%s,"category":"%s","location":"%s","dateOccurred":"%s"%s}
                """.formatted(type, objectMapper.writeValueAsString(title),
                objectMapper.writeValueAsString(description), category, location,
                occurred.toString(), question);
        MvcResult result = mvc.perform(post("/api/items")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generate(user);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class EventCaptureConfig {
        @org.springframework.context.annotation.Bean
        EventCapture eventCapture() {
            return new EventCapture();
        }

        @org.springframework.context.annotation.Bean
        @Primary
        BlockingMatchScoring blockingMatchScoring() {
            return new BlockingMatchScoring();
        }
    }

    /**
     * Pauses one scoring call so a test can prove the refresh transaction holds
     * item locks and never writes a snapshot from a superseded version.
     */
    static class BlockingMatchScoring extends MatchScoring {
        static final CountDownLatch entered = new CountDownLatch(1);
        static volatile CountDownLatch release = new CountDownLatch(1);
        private static final AtomicBoolean armed = new AtomicBoolean(false);

        static void arm() {
            release = new CountDownLatch(1);
            armed.set(true);
        }

        static void reset() {
            armed.set(false);
            release.countDown();
        }

        @Override
        public Optional<MatchScore> score(MatchProfile lost, MatchProfile found) {
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.score(lost, found);
        }
    }

    static class EventCapture {
        private final List<MatchCandidatesChangedEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.context.event.EventListener
        void onEvent(MatchCandidatesChangedEvent event) {
            events.add(event);
        }

        void clear() {
            events.clear();
        }

        List<MatchCandidatesChangedEvent> forItem(long itemId) {
            return events.stream().filter(event -> event.itemId() == itemId).toList();
        }

        MatchCandidatesChangedEvent lastForItem(long itemId) {
            List<MatchCandidatesChangedEvent> matches = forItem(itemId);
            assertThat(matches).isNotEmpty();
            return matches.get(matches.size() - 1);
        }
    }
}
