package com.lostfound;

import com.lostfound.dto.ClaimRequest;
import com.lostfound.exception.ApiException;
import com.lostfound.model.*;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.PhotoRepository;
import com.lostfound.repository.UserRepository;
import com.lostfound.service.ClaimService;
import com.lostfound.service.ItemService;
import com.lostfound.support.CleanFlywayConfig;
import com.lostfound.support.TestFixtures;
import com.lostfound.workflow.ClaimWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(CleanFlywayConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LifecycleWorkflowTest {

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
    ClaimWorkflow workflow;
    @Autowired
    ClaimService claimService;
    @Autowired
    ItemService itemService;

    TestFixtures fixtures;
    User alice;
    User bob;
    User stranger;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
        bob = fixtures.user("Bob", Role.USER);
        stranger = fixtures.user("Stranger", Role.USER);
    }

    private ClaimRequest request(String message, Long lostReportId) {
        return new ClaimRequest(message, lostReportId, null);
    }

    private ApiException apiError(Runnable action) {
        try {
            action.run();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("Expected an ApiException");
    }

    @Test
    void foundApprovalReservesAndRejectsCompetingPendingClaims() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Blue bottle");
        Claim winner = fixtures.claim(found, bob, ClaimStatus.PENDING);
        Claim loser = fixtures.claim(found, stranger, ClaimStatus.PENDING);

        workflow.decide(alice, winner.getId(), ClaimStatus.APPROVED);

        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(claims.findById(winner.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claims.findById(loser.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(items.findById(found.getId()).orElseThrow().getResolvedAt()).isNull();
    }

    @Test
    void approvalOfLostResponseCompletesWithoutHandover() {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost keys");
        Claim response = fixtures.claim(lost, bob, ClaimStatus.PENDING);

        workflow.decide(alice, response.getId(), ClaimStatus.APPROVED);

        Item storedItem = items.findById(lost.getId()).orElseThrow();
        Claim storedClaim = claims.findById(response.getId()).orElseThrow();
        assertThat(storedItem.getStatus()).isEqualTo(ItemStatus.RESOLVED);
        assertThat(storedItem.getResolvedAt()).isNotNull();
        assertThat(storedClaim.getStatus()).isEqualTo(ClaimStatus.COMPLETED);
        assertThat(storedClaim.isHandoverVerified()).isFalse();
        assertThat(storedClaim.getCompletedAt()).isNotNull();
    }

    @Test
    void releaseReopensReservationAndCancellationBlocksContact() {
        Item lost = fixtures.item(bob, ItemType.LOST, ItemStatus.OPEN, "Lost wallet");
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found wallet");
        Claim claim = workflow.create(bob, found.getId(), request("This is my wallet.", lost.getId()));
        workflow.decide(alice, claim.getId(), ClaimStatus.APPROVED);

        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(items.findById(lost.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(claimService.contact(bob, claim.getId()).claimant().email()).isEqualTo(bob.getEmail());

        workflow.release(alice, claim.getId(), "Owner picked it up already");

        Claim released = claims.findById(claim.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(ClaimStatus.CANCELLED);
        assertThat(released.getReleaseReason()).isEqualTo("Owner picked it up already");
        assertThat(released.getReleasedAt()).isNotNull();
        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.OPEN);
        assertThat(items.findById(lost.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.OPEN);

        ApiException denied = apiError(() -> claimService.contact(bob, claim.getId()));
        assertThat(denied.getCode()).isEqualTo("CONTACT_UNAVAILABLE");
        assertThat(denied.getStatus().value()).isEqualTo(403);
    }

    @Test
    void twoCompetingFoundReportsCannotBothReserveSameLostReport() {
        Item lost = fixtures.item(bob, ItemType.LOST, ItemStatus.OPEN, "Lost headphones");
        Item foundA = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found headphones A");
        Item foundB = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found headphones B");
        Claim claimA = workflow.create(bob, foundA.getId(), request("Mine, serial 111", lost.getId()));
        Claim claimB = workflow.create(bob, foundB.getId(), request("Mine, serial 222", lost.getId()));

        workflow.decide(alice, claimA.getId(), ClaimStatus.APPROVED);

        assertThat(items.findById(lost.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(claims.findById(claimB.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(items.findById(foundB.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.OPEN);

        ApiException error = apiError(() -> workflow.decide(alice, claimB.getId(), ClaimStatus.APPROVED));
        assertThat(error.getStatus().value()).isEqualTo(409);
        assertThat(error.getCode()).isEqualTo("CLAIM_NOT_PENDING");
    }

    @Test
    void simultaneousApprovalsAllowExactlyOneWinner() throws Exception {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Contested charger");
        Claim first = fixtures.claim(found, bob, ClaimStatus.PENDING);
        Claim second = fixtures.claim(found, stranger, ClaimStatus.PENDING);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<String> approveFirst = () -> {
            start.await(5, TimeUnit.SECONDS);
            return decideOutcome(first.getId());
        };
        Callable<String> approveSecond = () -> {
            start.await(5, TimeUnit.SECONDS);
            return decideOutcome(second.getId());
        };
        Future<String> one = pool.submit(approveFirst);
        Future<String> two = pool.submit(approveSecond);
        start.countDown();
        List<String> outcomes = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertThat(outcomes).containsExactlyInAnyOrder("OK", "CONFLICT");
        assertThat(claims.findById(first.getId()).orElseThrow().getStatus())
                .isIn(ClaimStatus.APPROVED, ClaimStatus.REJECTED);
        assertThat(claims.findById(second.getId()).orElseThrow().getStatus())
                .isIn(ClaimStatus.APPROVED, ClaimStatus.REJECTED);
        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
    }

    private String decideOutcome(Long claimId) {
        try {
            workflow.decide(alice, claimId, ClaimStatus.APPROVED);
            return "OK";
        } catch (ApiException ex) {
            return ex.getStatus().value() == 409 ? "CONFLICT" : "ERROR_" + ex.getCode();
        }
    }

    @Test
    void questionBearingClaimFailsClosedWithoutPolicy() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Laptop sleeve");
        found.setOwnershipQuestion("What sticker is inside?");
        items.save(found);

        ApiException error = apiError(() ->
                workflow.create(bob, found.getId(), request("It is mine.", null)));

        assertThat(error.getStatus().value()).isEqualTo(503);
        assertThat(error.getCode()).isEqualTo("EVIDENCE_UNAVAILABLE");
        assertThat(claims.findByItemIdOrderByCreatedAtDesc(found.getId())).isEmpty();
    }

    @Test
    void duplicateClaimIsRejectedWhileActive() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Grey scarf");
        workflow.create(bob, found.getId(), request("That is my scarf.", null));

        ApiException error = apiError(() ->
                workflow.create(bob, found.getId(), request("Still mine.", null)));

        assertThat(error.getStatus().value()).isEqualTo(409);
        assertThat(error.getCode()).isEqualTo("DUPLICATE_CLAIM");
    }

    @Test
    void manualResolutionRejectsPendingClaims() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Umbrella");
        Claim pending = fixtures.claim(found, bob, ClaimStatus.PENDING);

        itemService.updateStatus(alice, found.getId(), ItemStatus.RESOLVED);

        assertThat(claims.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESOLVED);
        ApiException error = apiError(() -> workflow.decide(alice, pending.getId(), ClaimStatus.APPROVED));
        assertThat(error.getCode()).isEqualTo("CLAIM_NOT_PENDING");
    }

    @Test
    void reservedReportRejectsEditsDeletionAndStatusChanges() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Reserved item");
        Claim claim = fixtures.claim(found, bob, ClaimStatus.PENDING);
        workflow.decide(alice, claim.getId(), ClaimStatus.APPROVED);

        ApiException edit = apiError(() -> itemService.update(alice, found.getId(),
                patch(found.getVersion(), "New title")));
        assertThat(edit.getCode()).isEqualTo("ITEM_RESERVED");
        ApiException delete = apiError(() -> itemService.delete(alice, found.getId()));
        assertThat(delete.getCode()).isEqualTo("ITEM_RESERVED");
        ApiException status = apiError(() ->
                itemService.updateStatus(alice, found.getId(), ItemStatus.CLOSED));
        assertThat(status.getCode()).isEqualTo("ITEM_RESERVED");
    }

    @Test
    void softDeleteHidesReportAndRejectsPendingClaims() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Deleted report");
        Claim pending = fixtures.claim(found, bob, ClaimStatus.PENDING);

        itemService.delete(alice, found.getId());

        assertThat(items.findById(found.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(claims.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.REJECTED);
        ApiException gone = apiError(() -> itemService.get(found.getId()));
        assertThat(gone.getCode()).isEqualTo("NOT_FOUND");
        ApiException claimGone = apiError(() ->
                workflow.create(stranger, found.getId(), request("Can I claim this?", null)));
        assertThat(claimGone.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void strangerCannotDecideOrRelease() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Guarded item");
        Claim claim = fixtures.claim(found, bob, ClaimStatus.PENDING);

        ApiException decide = apiError(() ->
                workflow.decide(stranger, claim.getId(), ClaimStatus.APPROVED));
        assertThat(decide.getStatus().value()).isEqualTo(403);

        workflow.decide(alice, claim.getId(), ClaimStatus.APPROVED);
        ApiException release = apiError(() -> workflow.release(stranger, claim.getId(), "no"));
        assertThat(release.getStatus().value()).isEqualTo(403);
    }

    private com.lostfound.dto.ItemPatchRequest patch(long version, String title) {
        com.lostfound.dto.ItemPatchRequest patch = new com.lostfound.dto.ItemPatchRequest();
        patch.setVersion(version);
        patch.setTitle(title);
        return patch;
    }
}
