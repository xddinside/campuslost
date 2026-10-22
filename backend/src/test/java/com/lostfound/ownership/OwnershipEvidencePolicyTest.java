package com.lostfound.ownership;

import com.lostfound.dto.ClaimRequest;
import com.lostfound.dto.ClaimResponse;
import com.lostfound.dto.ItemPatchRequest;
import com.lostfound.dto.ItemRequest;
import com.lostfound.dto.ItemResponse;
import com.lostfound.exception.ApiException;
import com.lostfound.model.Claim;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import com.lostfound.model.Role;
import com.lostfound.model.User;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.PhotoRepository;
import com.lostfound.repository.UserRepository;
import com.lostfound.service.ClaimService;
import com.lostfound.service.ItemService;
import com.lostfound.support.CleanFlywayConfig;
import com.lostfound.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(CleanFlywayConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnershipEvidencePolicyTest {

    private static final String QUESTION = "What is engraved inside the bottle lid?";
    private static final String ANSWER = "The initials A.C. are engraved inside the lid.";

    @Autowired UserRepository users;
    @Autowired ItemRepository items;
    @Autowired ClaimRepository claims;
    @Autowired PhotoRepository photos;
    @Autowired PasswordEncoder encoder;
    @Autowired ItemService itemService;
    @Autowired ClaimService claimService;

    TestFixtures fixtures;
    User alice;
    User bob;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
        bob = fixtures.user("Bob", Role.USER);
    }

    @Test
    void newFoundReportRequiresAMeaningfulQuestion() {
        ApiException missing = apiError(() -> itemService.create(alice, foundRequest(null)));
        assertThat(missing.getStatus().value()).isEqualTo(400);
        assertThat(missing.getCode()).isEqualTo("INVALID_QUESTION");
        assertThat(missing.getFieldErrors()).containsKey("ownershipQuestion");
        assertThat(items.findAll()).isEmpty();

        ApiException tooShort = apiError(() -> itemService.create(alice, foundRequest("Blue?")));
        assertThat(tooShort.getStatus().value()).isEqualTo(400);
        assertThat(tooShort.getFieldErrors()).containsKey("ownershipQuestion");
        assertThat(items.findAll()).isEmpty();

        ItemResponse createdResponse = itemService.create(alice, foundRequest("  " + QUESTION + "  "));
        Item created = items.findById(createdResponse.id()).orElseThrow();
        assertThat(created.getOwnershipQuestion()).isEqualTo(QUESTION);
        assertThat(created.getType()).isEqualTo(ItemType.FOUND);
    }

    @Test
    void lostReportsCannotCarryOwnershipQuestions() {
        ApiException error = apiError(() -> itemService.create(alice, lostRequest(QUESTION)));
        assertThat(error.getStatus().value()).isEqualTo(400);
        assertThat(error.getCode()).isEqualTo("INVALID_QUESTION");
        assertThat(items.findAll()).isEmpty();

        ItemResponse lostResponse = itemService.create(alice, lostRequest(null));
        Item lost = items.findById(lostResponse.id()).orElseThrow();
        assertThat(lost.getType()).isEqualTo(ItemType.LOST);
        assertThat(lost.getOwnershipQuestion()).isNull();
    }

    @Test
    void legacyFoundReportWithoutQuestionStaysEditable() {
        Item legacy = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Legacy found");
        ItemPatchRequest patch = new ItemPatchRequest();
        patch.setVersion(legacy.getVersion());
        patch.setTitle("Legacy found, renamed");

        ItemResponse updatedResponse = itemService.update(alice, legacy.getId(), patch);
        Item updated = items.findById(updatedResponse.id()).orElseThrow();

        assertThat(updated.getTitle()).isEqualTo("Legacy found, renamed");
        assertThat(updated.getOwnershipQuestion()).isNull();
    }

    @Test
    void claimRequiresAnAnswerAndPersistsTheExactSnapshot() {
        ItemResponse foundResponse = itemService.create(alice, foundRequest(QUESTION));
        Item found = items.findById(foundResponse.id()).orElseThrow();

        ApiException missing = apiError(() -> claimService.create(bob, found.getId(),
                new ClaimRequest("This is definitely mine.", null, null)));
        assertThat(missing.getStatus().value()).isEqualTo(400);
        assertThat(missing.getCode()).isEqualTo("INVALID_EVIDENCE");
        assertThat(missing.getFieldErrors()).containsKey("ownershipAnswer");
        assertThat(claims.findByItemIdOrderByCreatedAtDesc(found.getId())).isEmpty();

        ApiException shortAnswer = apiError(() -> claimService.create(bob, found.getId(),
                new ClaimRequest("This is definitely mine.", null, "A.C.")));
        assertThat(shortAnswer.getStatus().value()).isEqualTo(400);
        assertThat(shortAnswer.getFieldErrors()).containsKey("ownershipAnswer");
        assertThat(claims.findByItemIdOrderByCreatedAtDesc(found.getId())).isEmpty();

        ApiException blankAnswer = apiError(() -> claimService.create(bob, found.getId(),
                new ClaimRequest("This is definitely mine.", null, "   ")));
        assertThat(blankAnswer.getStatus().value()).isEqualTo(400);
        assertThat(blankAnswer.getCode()).isEqualTo("INVALID_EVIDENCE");
        assertThat(claims.findByItemIdOrderByCreatedAtDesc(found.getId())).isEmpty();

        ApiException longAnswer = apiError(() -> claimService.create(bob, found.getId(),
                new ClaimRequest("This is definitely mine.", null, "x".repeat(2001))));
        assertThat(longAnswer.getStatus().value()).isEqualTo(400);
        assertThat(longAnswer.getCode()).isEqualTo("INVALID_EVIDENCE");
        assertThat(longAnswer.getFieldErrors()).containsKey("ownershipAnswer");
        assertThat(claims.findByItemIdOrderByCreatedAtDesc(found.getId())).isEmpty();

        ClaimResponse claimResponse = claimService.create(bob, found.getId(),
                new ClaimRequest("This is definitely mine.", null, "  " + ANSWER + "  "));
        Claim claim = claims.findById(claimResponse.id()).orElseThrow();

        assertThat(claim.getOwnershipQuestionSnapshot()).isEqualTo(QUESTION);
        assertThat(claim.getOwnershipAnswer()).isEqualTo(ANSWER);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PENDING);
    }

    @Test
    void lostResponseStillWorksAndRejectsAnOwnershipAnswer() {
        ItemResponse lostResponse = itemService.create(alice, lostRequest(null));
        Item lost = items.findById(lostResponse.id()).orElseThrow();

        ApiException rejected = apiError(() -> claimService.create(bob, lost.getId(),
                new ClaimRequest("I saw it near the library desk.", null, ANSWER)));
        assertThat(rejected.getStatus().value()).isEqualTo(400);
        assertThat(rejected.getCode()).isEqualTo("INVALID_EVIDENCE");

        ClaimResponse responseDto = claimService.create(bob, lost.getId(),
                new ClaimRequest("I saw it near the library desk.", null, null));
        Claim response = claims.findById(responseDto.id()).orElseThrow();
        assertThat(response.getOwnershipQuestionSnapshot()).isNull();
        assertThat(response.getOwnershipAnswer()).isNull();
    }

    @Test
    void pendingClaimsFreezeTheQuestionAndPastSnapshotsNeverChange() {
        ItemResponse foundResponse = itemService.create(alice, foundRequest(QUESTION));
        Item found = items.findById(foundResponse.id()).orElseThrow();
        ClaimResponse firstResponse = claimService.create(bob, found.getId(),
                new ClaimRequest("The owner will recognise this.", null, ANSWER));
        Long firstId = firstResponse.id();

        ApiException frozen = apiError(() -> itemService.update(alice, found.getId(),
                questionPatch(found.getVersion(), "What colour is the hidden sticker?")));
        assertThat(frozen.getStatus().value()).isEqualTo(409);
        assertThat(frozen.getCode()).isEqualTo("QUESTION_FROZEN");
        assertThat(claims.findById(firstId).orElseThrow().getOwnershipQuestionSnapshot())
                .isEqualTo(QUESTION);

        claimService.decide(alice, firstId,
                new com.lostfound.dto.ClaimDecisionRequest(ClaimStatus.REJECTED));

        String revised = "What words are printed under the bottle lid?";
        ItemResponse updatedResponse = itemService.update(alice, found.getId(),
                questionPatch(found.getVersion(), revised));
        Item updated = items.findById(updatedResponse.id()).orElseThrow();
        assertThat(updated.getOwnershipQuestion()).isEqualTo(revised);
        assertThat(claims.findById(firstId).orElseThrow().getOwnershipQuestionSnapshot())
                .isEqualTo(QUESTION);

        ClaimResponse secondResponse = claimService.create(bob, found.getId(),
                new ClaimRequest("Here is the detail you asked for.", null, ANSWER));
        Claim second = claims.findById(secondResponse.id()).orElseThrow();
        assertThat(second.getOwnershipQuestionSnapshot()).isEqualTo(revised);
        assertThat(second.getOwnershipAnswer()).isEqualTo(ANSWER);
        assertThat(firstId).isNotEqualTo(second.getId());
    }

    @Test
    void cancelledClaimsRemainPrivateHistoryAndAllowResubmission() {
        ItemResponse foundResponse = itemService.create(alice, foundRequest(QUESTION));
        Item found = items.findById(foundResponse.id()).orElseThrow();
        ClaimResponse cancelledResponse = claimService.create(bob, found.getId(),
                new ClaimRequest("I believe this belongs to me.", null, ANSWER));
        Long cancelledId = cancelledResponse.id();
        claimService.cancel(bob, cancelledId);

        ClaimResponse retriedResponse = claimService.create(bob, found.getId(),
                new ClaimRequest("Trying again with the same detail.", null, ANSWER));

        assertThat(claims.findById(cancelledId).orElseThrow().getStatus())
                .isEqualTo(ClaimStatus.CANCELLED);
        assertThat(claims.findById(cancelledId).orElseThrow().getOwnershipAnswer())
                .isEqualTo(ANSWER);
        assertThat(claims.findById(retriedResponse.id()).orElseThrow().getStatus())
                .isEqualTo(ClaimStatus.PENDING);
    }

    @Test
    void concurrentQuestionEditAndClaimKeepSnapshotConsistent() throws Exception {
        for (int round = 0; round < 4; round++) {
            ItemResponse foundResponse = itemService.create(alice, foundRequest(QUESTION));
            Item found = items.findById(foundResponse.id()).orElseThrow();
            String revised = "What tiny mark is stamped under the lid?";
            ItemPatchRequest patch = questionPatch(found.getVersion(), revised);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Callable<String> createClaim = () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    claimService.create(bob, found.getId(),
                            new ClaimRequest("A private answer for this item.", null, ANSWER));
                    return "OK";
                } catch (ApiException ex) {
                    return "ERROR_" + ex.getCode();
                }
            };
            Callable<String> editQuestion = () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    itemService.update(alice, found.getId(), patch);
                    return "OK";
                } catch (ApiException ex) {
                    return "CONFLICT_" + ex.getCode();
                }
            };
            Future<String> claimOutcome = pool.submit(createClaim);
            Future<String> editOutcome = pool.submit(editQuestion);
            start.countDown();
            String claimResult = claimOutcome.get(30, TimeUnit.SECONDS);
            String editResult = editOutcome.get(30, TimeUnit.SECONDS);
            pool.shutdownNow();

            assertThat(claimResult).as("round %s claim outcome", round).isEqualTo("OK");
            assertThat(editResult).as("round %s edit outcome", round)
                    .isIn("OK", "CONFLICT_QUESTION_FROZEN");

            Item storedItem = items.findById(found.getId()).orElseThrow();
            List<Claim> storedClaims = claims.findByItemIdOrderByCreatedAtDesc(found.getId());
            assertThat(storedClaims).hasSize(1);
            assertThat(storedClaims.getFirst().getOwnershipQuestionSnapshot())
                    .as("round %s snapshot must match the prompt under lock", round)
                    .isEqualTo(storedItem.getOwnershipQuestion());
            assertThat(storedClaims.getFirst().getOwnershipAnswer()).isEqualTo(ANSWER);

            fixtures.reset();
            alice = fixtures.user("Alice", Role.USER);
            bob = fixtures.user("Bob", Role.USER);
        }
    }

    private ItemRequest foundRequest(String question) {
        return new ItemRequest(ItemType.FOUND, "Blue bottle", "A detailed description of the bottle.",
                "Other", "University Gym", Instant.now().minusSeconds(3600), question, null);
    }

    private ItemRequest lostRequest(String question) {
        return new ItemRequest(ItemType.LOST, "Lost card", "A detailed description of the lost card.",
                "ID & Documents", "Student Union", Instant.now().minusSeconds(3600), question, null);
    }

    private ItemPatchRequest questionPatch(long version, String question) {
        ItemPatchRequest patch = new ItemPatchRequest();
        patch.setVersion(version);
        patch.setOwnershipQuestion(question);
        return patch;
    }

    private ApiException apiError(Runnable action) {
        try {
            action.run();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("Expected an ApiException");
    }
}
