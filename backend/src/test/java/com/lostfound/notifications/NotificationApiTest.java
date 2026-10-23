package com.lostfound.notifications;

import com.lostfound.contract.ClaimChangedEvent;
import com.lostfound.contract.MatchCandidate;
import com.lostfound.matching.MatchCandidateEntity;
import com.lostfound.contract.MatchCandidatesChangedEvent;
import com.lostfound.dto.ClaimRequest;
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
import com.lostfound.security.JwtService;
import com.lostfound.support.CleanFlywayConfig;
import com.lostfound.support.TestFixtures;
import com.lostfound.handover.HandoverChallengeRepository;
import com.lostfound.handover.HandoverCodeResponse;
import com.lostfound.handover.HandoverService;
import com.lostfound.workflow.ClaimWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(CleanFlywayConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationApiTest {

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
    NotificationRepository notifications;
    @Autowired
    NotificationService service;
    @Autowired
    ClaimWorkflow workflow;
    @Autowired
    HandoverService handover;
    @Autowired
    HandoverChallengeRepository challenges;
    @Autowired
    com.lostfound.matching.MatchCandidateRepository matchCandidates;


    TestFixtures fixtures;
    User alice;
    User bob;
    User carol;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        challenges.deleteAll();
        notifications.deleteAll();
        matchCandidates.deleteAll();
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
        bob = fixtures.user("Bob", Role.USER);
        carol = fixtures.user("Carol", Role.USER);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generate(user);
    }

    /**
     * Delivers the snapshot path end to end: the committed refresh writes the
     * persisted candidate row first, then the snapshot event reconciles the
     * notification rows against that table.
     */
    private void publishPair(Item lost, Item found, int score) {
        MatchCandidateEntity row = new MatchCandidateEntity();
        row.setLostItem(lost);
        row.setFoundItem(found);
        row.setLostItemVersion(lost.getVersion());
        row.setFoundItemVersion(found.getVersion());
        row.setScore(score);
        row.setReasons("same category\nnear the library");
        matchCandidates.save(row);
        service.handleMatchSnapshot(new MatchCandidatesChangedEvent(lost.getId(),
                lost.getVersion(), List.of(new MatchCandidate(lost.getId(), found.getId(),
                lost.getReporter().getId(), found.getReporter().getId(), score,
                List.of("same category", "near the library"), lost.getVersion(),
                found.getVersion()))));
    }

    private ClaimChangedEvent claimEvent(Claim claim) {
        return new ClaimChangedEvent(claim.getId(), claim.getItem().getId(),
                claim.getItem().getType(),
                claim.getLostReport() == null ? null : claim.getLostReport().getId(),
                claim.getClaimant().getId(), claim.getItem().getReporter().getId(),
                claim.getStatus(), claim.isHandoverVerified());
    }

    private Notification directNotification(Long recipientId, NotificationType type,
                                            String dedupeKey, Long claimId, Instant createdAt) {
        Notification row = new Notification();
        row.setRecipientId(recipientId);
        row.setType(type);
        row.setDedupeKey(dedupeKey);
        row.setClaimId(claimId);
        row.setTitle("Queued alert");
        row.setBody("A previously queued alert.");
        row.setCreatedAt(createdAt);
        return notifications.save(row);
    }

    @Test
    void inboxIsScopedShapedAndReadStateSurvivesRefresh() throws Exception {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Blue bottle");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Found bottle");
        publishPair(lost, found, 82);

        MvcResult aliceList = mvc.perform(get("/api/me/notifications")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.nextBeforeId").doesNotExist())
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].type").value("MATCH_FOUND"))
                .andExpect(jsonPath("$.notifications[0].title").value(containsString("Blue bottle")))
                .andExpect(jsonPath("$.notifications[0].body").value(containsString("same category")))
                .andExpect(jsonPath("$.notifications[0].itemId").value(found.getId()))
                .andExpect(jsonPath("$.notifications[0].lostReportId").value(lost.getId()))
                .andExpect(jsonPath("$.notifications[0].claimId").doesNotExist())
                .andExpect(jsonPath("$.notifications[0].readAt").doesNotExist())
                .andExpect(content().string(not(containsString("@test.dev"))))
                .andExpect(content().string(not(containsString("555-"))))
                .andReturn();
        long aliceNotificationId = notifications.findAll().stream()
                .filter(row -> row.getRecipientId().equals(alice.getId()))
                .findFirst().orElseThrow().getId();

        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.notifications[0].itemId").value(found.getId()))
                .andExpect(jsonPath("$.notifications[0].lostReportId").doesNotExist());

        // Another account cannot see or mark this row.
        mvc.perform(patch("/api/me/notifications/" + aliceNotificationId + "/read")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        MvcResult firstRead = mvc.perform(patch("/api/me/notifications/"
                        + aliceNotificationId + "/read")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceNotificationId))
                .andExpect(jsonPath("$.readAt").exists())
                .andReturn();
        String readAt = com.jayway.jsonpath.JsonPath.read(
                firstRead.getResponse().getContentAsString(), "$.readAt");

        mvc.perform(patch("/api/me/notifications/" + aliceNotificationId + "/read")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").value(readAt));
        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.notifications[0].readAt").value(readAt));

        // The stored row is unchanged by a refresh.
        assertThat(notifications.findById(aliceNotificationId).orElseThrow().getReadAt())
                .isNotNull();
        assertThat(aliceList.getResponse().getContentAsString()).contains("MATCH_FOUND");
    }

    @Test
    void pagingUsesKeysetCursorAndRejectsInvalidLimits() throws Exception {
        for (int i = 0; i < 3; i++) {
            Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found item " + i);
            Claim claim = fixtures.claim(found, bob, ClaimStatus.APPROVED);
            service.handleClaimChanged(claimEvent(claim));
        }

        MvcResult pageOne = mvc.perform(get("/api/me/notifications")
                        .param("limit", "2")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(2))
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.nextBeforeId").isNumber())
                .andReturn();
        Number cursor = com.jayway.jsonpath.JsonPath.read(
                pageOne.getResponse().getContentAsString(), "$.nextBeforeId");
        long beforeId = cursor.longValue();

        mvc.perform(get("/api/me/notifications")
                        .param("limit", "2")
                        .param("beforeId", String.valueOf(beforeId))
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.nextBeforeId").doesNotExist());

        mvc.perform(get("/api/me/notifications").param("limit", "0")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        mvc.perform(get("/api/me/notifications").param("limit", "51")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        mvc.perform(get("/api/me/notifications").param("limit", "many")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/me/notifications").param("beforeId", "0")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void readAllMarksCurrentRowsAndLeavesLaterArrivalsUnread() throws Exception {
        Item foundOne = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Wallet");
        Claim first = fixtures.claim(foundOne, bob, ClaimStatus.APPROVED);
        service.handleClaimChanged(claimEvent(first));
        Item foundTwo = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Keys");
        Claim second = fixtures.claim(foundTwo, bob, ClaimStatus.REJECTED);
        service.handleClaimChanged(claimEvent(second));

        // A row committed with a future timestamp stands in for a concurrent
        // arrival that must survive this read-all.
        Notification later = directNotification(bob.getId(), NotificationType.CLAIM_APPROVED,
                "claim:future:approved", first.getId(), Instant.now().plusSeconds(30));

        mvc.perform(post("/api/me/notifications/read-all")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.notifications[0].id").value(later.getId()))
                .andExpect(jsonPath("$.notifications[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.notifications[1].readAt").exists());

        Item foundThree = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Umbrella");
        Claim third = fixtures.claim(foundThree, bob, ClaimStatus.APPROVED);
        service.handleClaimChanged(claimEvent(third));
        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));
    }

    /**
     * A snapshot produced before the opposite report changed can arrive late
     * with an older (possibly empty) candidate list while the source's own
     * version is unchanged. It must not deactivate alerts the newer persisted
     * snapshot created.
     */
    @Test
    void staleEmptySnapshotCannotHidePersistedMatchAlerts() throws Exception {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost camera");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Found camera");
        publishPair(lost, found, 82);
        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.unreadCount").value(1));

        // The older empty snapshot replays at an unchanged source version.
        service.handleMatchSnapshot(new MatchCandidatesChangedEvent(
                lost.getId(), lost.getVersion(), List.of()));

        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.notifications.length()").value(1));
        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    /**
     * Both cutoff orders, deterministically: an alert committed before the
     * read-all is marked with its exact id, and an alert committed after it
     * stays unread. No fabricated timestamps stand in for real delivery.
     */
    @Test
    void readAllMarksCommittedArrivalsAndLeavesLaterOnesUnread() throws Exception {
        // Order A: delivery commits before the read-all request.
        Item foundOne = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "First umbrella");
        Claim first = fixtures.claim(foundOne, bob, ClaimStatus.APPROVED);
        service.handleClaimChanged(claimEvent(first));
        Notification before = notifications.findAll().stream()
                .filter(n -> n.getRecipientId().equals(bob.getId()))
                .findFirst().orElseThrow();

        mvc.perform(post("/api/me/notifications/read-all")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
        assertThat(notifications.findById(before.getId()).orElseThrow().getReadAt())
                .as("the alert that committed before the cutoff is marked read")
                .isNotNull();

        // Order B: the next delivery commits after the cutoff and survives.
        Item foundTwo = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Second umbrella");
        Claim second = fixtures.claim(foundTwo, bob, ClaimStatus.APPROVED);
        service.handleClaimChanged(claimEvent(second));
        Notification after = notifications.findAll().stream()
                .filter(n -> n.getRecipientId().equals(bob.getId()) && n.getId() != before.getId())
                .findFirst().orElseThrow();

        assertThat(notifications.findById(after.getId()).orElseThrow().getReadAt()).isNull();
        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.notifications[0].id").value(after.getId()))
                .andExpect(jsonPath("$.notifications[0].claimId").value(second.getId()));
    }

    @Test
    void deletedOrResolvedMatchesDisappearFromListAndUnreadCount() throws Exception {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost gloves");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Found gloves");
        publishPair(lost, found, 80);

        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.notifications.length()").value(1));

        found.setStatus(ItemStatus.RESOLVED);
        found.setResolvedAt(Instant.now());
        found = items.save(found);

        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.notifications.length()").value(0));

        found.setStatus(ItemStatus.OPEN);
        found.setResolvedAt(null);
        items.save(found);
        lost.setDeletedAt(Instant.now());
        items.save(lost);

        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.notifications.length()").value(0));
    }

    @Test
    void approvalSuppressesMatchAlertAndNotifiesOnlyTheClaimant() throws Exception {
        Item lost = fixtures.item(bob, ItemType.LOST, ItemStatus.OPEN, "Lost wallet");
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found wallet");
        publishPair(lost, found, 86);

        Claim claim = workflow.create(bob, found.getId(),
                new ClaimRequest("It has a scratch on the back.", lost.getId(), null));
        workflow.decide(alice, claim.getId(), ClaimStatus.APPROVED);

        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].type").value("CLAIM_APPROVED"))
                .andExpect(jsonPath("$.notifications[0].itemId").value(found.getId()))
                .andExpect(jsonPath("$.notifications[0].claimId").value(claim.getId()))
                .andExpect(jsonPath("$.notifications[0].body").value(not(containsString("scratch"))));

        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.notifications.length()").value(0));
    }

    @Test
    void verifiedHandoverNotifiesBothParticipants() throws Exception {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.RESERVED, "AirPods");
        Claim claim = fixtures.claim(found, bob, ClaimStatus.COMPLETED);
        claim.setHandoverVerified(true);
        claims.save(claim);
        service.handleClaimChanged(claimEvent(claim));

        for (User participant : List.of(alice, bob)) {
            mvc.perform(get("/api/me/notifications")
                            .header("Authorization", bearer(participant)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notifications.length()").value(1))
                    .andExpect(jsonPath("$.notifications[0].type").value("HANDOVER_COMPLETED"))
                    .andExpect(jsonPath("$.notifications[0].claimId").value(claim.getId()))
                    .andExpect(jsonPath("$.notifications[0].itemId").value(found.getId()));
        }
    }

    @Test
    void concurrentReadCallsSetReadAtOnceAndNeverFail() throws Exception {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Notebook");
        Claim claim = fixtures.claim(found, bob, ClaimStatus.APPROVED);
        service.handleClaimChanged(claimEvent(claim));
        Long notificationId = notifications.findVisiblePage(bob.getId(), null,
                        NotificationType.MATCH_FOUND, ItemStatus.OPEN,
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .getFirst().getId();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attempts.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(patch("/api/me/notifications/" + notificationId + "/read")
                                .header("Authorization", bearer(bob)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> attempt : attempts) {
            assertThat(attempt.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdownNow();

        Notification stored = notifications.findById(notificationId).orElseThrow();
        assertThat(stored.getReadAt()).isNotNull();
        mvc.perform(get("/api/me/notifications").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.notifications[0].readAt").exists());
    }

    @Test
    void verifiedPickupDeliversCompletionToBothInboxes() throws Exception {
        // End-to-end delivery: the completion notification must arrive because
        // of a real code verification, not a directly-set flag.
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found campus card");
        Claim claim = workflow.create(bob, found.getId(),
                new ClaimRequest("It is mine; the photo matches my card.", null, null));
        workflow.decide(alice, claim.getId(), ClaimStatus.APPROVED);
        HandoverCodeResponse issued = handover.issue(bob, claim.getId());
        handover.verify(alice, claim.getId(), issued.code());

        for (User participant : List.of(alice, bob)) {
            mvc.perform(get("/api/me/notifications")
                            .header("Authorization", bearer(participant)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notifications[0].type").value("HANDOVER_COMPLETED"))
                    .andExpect(jsonPath("$.notifications[0].claimId").value(claim.getId()));
        }
    }

    @Test
    void inboxRequiresAValidSession() throws Exception {
        mvc.perform(get("/api/me/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/me/notifications")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/notifications/read-all")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
