package com.lostfound.notifications;

import com.lostfound.contract.ClaimChangedEvent;
import com.lostfound.contract.MatchCandidate;
import com.lostfound.contract.MatchCandidatesChangedEvent;
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
import com.lostfound.support.CleanFlywayConfig;
import com.lostfound.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(CleanFlywayConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationDeliveryTest {

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
    NotificationRepository notifications;
    @Autowired
    NotificationService service;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    PlatformTransactionManager transactionManager;

    TestFixtures fixtures;
    User alice;
    User bob;
    User carol;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        notifications.deleteAll();
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
        bob = fixtures.user("Bob", Role.USER);
        carol = fixtures.user("Carol", Role.USER);
    }

    private MatchCandidate candidate(Item lost, Item found, int score, String... reasons) {
        return new MatchCandidate(lost.getId(), found.getId(), lost.getReporter().getId(),
                found.getReporter().getId(), score, List.of(reasons),
                lost.getVersion(), found.getVersion());
    }

    private MatchCandidatesChangedEvent snapshot(Item source, MatchCandidate... candidates) {
        return new MatchCandidatesChangedEvent(source.getId(), source.getVersion(),
                List.of(candidates));
    }

    private List<Notification> forRecipient(User user) {
        return notifications.findAll().stream()
                .filter(row -> row.getRecipientId().equals(user.getId()))
                .toList();
    }

    @Test
    void oneQualifyingPairNotifiesBothAuthorsOnce() {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Blue bottle");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Found bottle");

        service.handleMatchSnapshot(snapshot(lost, candidate(lost, found, 78,
                "same category", "Central Library", "date within 1 day")));

        List<Notification> rows = notifications.findAll();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(Notification::getRecipientId)
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getType()).isEqualTo(NotificationType.MATCH_FOUND);
            assertThat(row.getDedupeKey()).isEqualTo("match:" + lost.getId() + ":" + found.getId());
            assertThat(row.getScore()).isEqualTo(78);
            assertThat(row.getLostItemVersion()).isEqualTo(lost.getVersion());
            assertThat(row.getFoundItemVersion()).isEqualTo(found.getVersion());
            assertThat(row.isActive()).isTrue();
            assertThat(row.getReadAt()).isNull();
        });
        Notification toLostAuthor = rows.stream()
                .filter(row -> row.getRecipientId().equals(alice.getId()))
                .findFirst().orElseThrow();
        Notification toFoundAuthor = rows.stream()
                .filter(row -> row.getRecipientId().equals(bob.getId()))
                .findFirst().orElseThrow();
        assertThat(toLostAuthor.getTitle()).contains("Blue bottle");
        assertThat(toLostAuthor.getBody()).contains("same category", "newly found");
        assertThat(toFoundAuthor.getTitle()).contains("Found bottle");
        assertThat(toFoundAuthor.getBody()).contains("same category", "lost report");
        assertThat(toLostAuthor.getBody()).doesNotContain("@test.dev", "555-");
    }

    @Test
    void lowScoreSameAuthorStaleAndTerminalPairsProduceNothing() {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost keys");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Found keys");

        service.handleMatchSnapshot(snapshot(lost, candidate(lost, found, 59, "weak")));
        assertThat(notifications.findAll()).isEmpty();

        Item ownFound = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Alice found keys");
        service.handleMatchSnapshot(snapshot(lost, candidate(lost, ownFound, 90, "same author")));
        assertThat(notifications.findAll()).isEmpty();

        MatchCandidate staleVersion = new MatchCandidate(lost.getId(), found.getId(),
                alice.getId(), bob.getId(), 90, List.of("stale"),
                lost.getVersion() + 5, found.getVersion());
        service.handleMatchSnapshot(new MatchCandidatesChangedEvent(lost.getId(),
                lost.getVersion(), List.of(staleVersion)));
        assertThat(notifications.findAll()).isEmpty();

        Item resolved = fixtures.item(bob, ItemType.FOUND, ItemStatus.RESOLVED, "Resolved keys");
        service.handleMatchSnapshot(snapshot(lost, candidate(lost, resolved, 90, "resolved")));
        assertThat(notifications.findAll()).isEmpty();

        service.handleMatchSnapshot(new MatchCandidatesChangedEvent(lost.getId(),
                lost.getVersion() + 1, List.of(candidate(lost, found, 90, "stale source"))));
        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    void replayUpdatesSnapshotWithoutResettingReadState() {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost scarf");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Found scarf");

        service.handleMatchSnapshot(snapshot(lost, candidate(lost, found, 70, "category")));
        Notification bobRow = forRecipient(bob).getFirst();
        Instant createdAt = bobRow.getCreatedAt();
        Instant readAt = Instant.parse("2026-01-01T00:00:00Z");
        bobRow.setReadAt(readAt);
        notifications.save(bobRow);

        service.handleMatchSnapshot(snapshot(lost, candidate(lost, found, 91,
                "category", "same building", "same day")));

        List<Notification> rows = notifications.findAll();
        assertThat(rows).hasSize(2);
        Notification replayed = notifications.findById(bobRow.getId()).orElseThrow();
        assertThat(replayed.getScore()).isEqualTo(91);
        assertThat(replayed.getReasons()).contains("same building");
        assertThat(replayed.getCreatedAt()).isEqualTo(createdAt);
        assertThat(replayed.getReadAt()).isEqualTo(readAt);
        assertThat(replayed.isActive()).isTrue();
    }

    @Test
    void emptySnapshotDeactivatesPairButKeepsHistoryAndReadState() {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost gloves");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Found gloves");
        service.handleMatchSnapshot(snapshot(lost, candidate(lost, found, 88, "category")));

        Notification bobRow = forRecipient(bob).getFirst();
        Instant readAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        bobRow.setReadAt(readAt);
        notifications.save(bobRow);

        service.handleMatchSnapshot(snapshot(lost));

        List<Notification> rows = notifications.findAll();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.isActive()).isFalse());
        Notification preserved = notifications.findById(bobRow.getId()).orElseThrow();
        assertThat(preserved.getReadAt()).isEqualTo(readAt);

        // A later qualifying snapshot revives the pair without resetting readAt.
        service.handleMatchSnapshot(snapshot(lost, candidate(lost, found, 88, "category")));
        Notification revived = notifications.findById(bobRow.getId()).orElseThrow();
        assertThat(revived.isActive()).isTrue();
        assertThat(revived.getReadAt()).isEqualTo(readAt);
    }

    @Test
    void staleEmptySnapshotDoesNotDeactivateAnything() {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost charger");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Found charger");
        service.handleMatchSnapshot(snapshot(lost, candidate(lost, found, 85, "category")));

        service.handleMatchSnapshot(new MatchCandidatesChangedEvent(lost.getId(),
                lost.getVersion() + 1, List.of()));

        assertThat(notifications.findAll()).allSatisfy(row -> assertThat(row.isActive()).isTrue());
    }

    @Test
    void eventPublishedInTransactionDeliversOnlyAfterCommit() {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost notebook");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Found notebook");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            publisher.publishEvent(snapshot(lost, candidate(lost, found, 77, "category")));
            assertThat(notifications.findAll()).isEmpty();
        });
        assertThat(notifications.findAll()).hasSize(2);

        Item otherLost = fixtures.item(carol, ItemType.LOST, ItemStatus.OPEN, "Lost pen");
        transaction.executeWithoutResult(status -> {
            publisher.publishEvent(snapshot(otherLost,
                    candidate(otherLost, found, 77, "category")));
            status.setRollbackOnly();
        });
        assertThat(notifications.findAll()).hasSize(2);
    }

    @Test
    void concurrentDuplicateDeliveryKeepsOneRowPerRecipient() throws Exception {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Contested bottle");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Contested bottle found");
        MatchCandidatesChangedEvent event = snapshot(lost, candidate(lost, found, 80, "category"));

        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            work.add(() -> {
                start.await(5, TimeUnit.SECONDS);
                service.handleMatchSnapshot(event);
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : work) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(notifications.findAll()).hasSize(2);
        assertThat(forRecipient(alice)).hasSize(1);
        assertThat(forRecipient(bob)).hasSize(1);
    }

    @Test
    void claimTransitionsNotifyTheRightPeople() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "iPhone");
        Claim approved = fixtures.claim(found, bob, ClaimStatus.APPROVED);
        service.handleClaimChanged(claimEvent(approved, false));

        assertThat(forRecipient(bob)).singleElement().satisfies(row -> {
            assertThat(row.getType()).isEqualTo(NotificationType.CLAIM_APPROVED);
            assertThat(row.getDedupeKey()).isEqualTo("claim:" + approved.getId() + ":approved");
            assertThat(row.getBody()).contains("iPhone", "approved");
        });
        assertThat(forRecipient(alice)).isEmpty();

        // Replaying must not duplicate or reset read state.
        Notification read = forRecipient(bob).getFirst();
        read.setReadAt(Instant.now());
        notifications.save(read);
        service.handleClaimChanged(claimEvent(approved, false));
        assertThat(forRecipient(bob)).hasSize(1);
        assertThat(forRecipient(bob).getFirst().getReadAt()).isNotNull();

        Claim rejected = fixtures.claim(found, carol, ClaimStatus.REJECTED);
        service.handleClaimChanged(claimEvent(rejected, false));
        assertThat(forRecipient(carol)).singleElement().satisfies(row ->
                assertThat(row.getType()).isEqualTo(NotificationType.CLAIM_REJECTED));

        Item pickedUp = fixtures.item(alice, ItemType.FOUND, ItemStatus.RESERVED, "iPhone pickup");
        Claim completed = fixtures.claim(pickedUp, carol, ClaimStatus.COMPLETED);
        completed.setHandoverVerified(true);
        claims.save(completed);
        service.handleClaimChanged(claimEvent(completed, true));
        assertThat(forRecipient(carol)).filteredOn(row ->
                        row.getType() == NotificationType.HANDOVER_COMPLETED)
                .singleElement()
                .satisfies(row -> assertThat(row.getDedupeKey())
                        .isEqualTo("claim:" + completed.getId() + ":completed"));
        assertThat(forRecipient(alice)).filteredOn(row ->
                        row.getType() == NotificationType.HANDOVER_COMPLETED)
                .singleElement();

        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost umbrella");
        Claim lostResponse = fixtures.claim(lost, bob, ClaimStatus.COMPLETED);
        service.handleClaimChanged(claimEvent(lostResponse, false));
        assertThat(forRecipient(bob)).filteredOn(row ->
                        row.getType() == NotificationType.CLAIM_APPROVED
                                && row.getDedupeKey().contains(String.valueOf(lostResponse.getId())))
                .singleElement()
                .satisfies(row -> assertThat(row.getTitle()).isEqualTo("Response accepted"));
    }

    @Test
    void unverifiedFoundCompletionProducesNoHandoverAlert() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Legacy item");
        Claim legacy = fixtures.claim(found, bob, ClaimStatus.COMPLETED);
        service.handleClaimChanged(claimEvent(legacy, false));

        assertThat(notifications.findAll()).isEmpty();
    }

    private ClaimChangedEvent claimEvent(Claim claim, boolean handoverVerified) {
        return new ClaimChangedEvent(claim.getId(), claim.getItem().getId(),
                claim.getItem().getType(),
                claim.getLostReport() == null ? null : claim.getLostReport().getId(),
                claim.getClaimant().getId(), claim.getItem().getReporter().getId(),
                claim.getStatus(), handoverVerified);
    }
}
