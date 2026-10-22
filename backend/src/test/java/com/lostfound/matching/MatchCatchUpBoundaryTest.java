package com.lostfound.matching;

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
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restart catch-up with a batch size of one: every source must still publish
 * its complete candidate snapshot, so a pair whose two endpoints land in
 * different batches survives the delete/store cycle of each batch.
 */
@SpringBootTest(properties = "app.matching.catch-up-max-items=1")
@ActiveProfiles("test")
@Import({CleanFlywayConfig.class, MatchingApiTest.EventCaptureConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MatchCatchUpBoundaryTest {

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
    MatchCandidateRepository candidates;
    @Autowired
    MatchRefreshService refreshService;
    @Autowired
    MatchingApiTest.EventCapture events;

    TestFixtures fixtures;
    User alice;
    User bob;
    User carol;

    @BeforeEach
    void setUp() {
        candidates.deleteAll();
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
        bob = fixtures.user("Bob", Role.USER);
        carol = fixtures.user("Carol", Role.USER);
        events.clear();
    }

    @Test
    void batchesStillPublishCompleteSnapshotsAcrossBoundaries() {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Silver water bottle");
        Item found = fixtures.item(bob, ItemType.FOUND, ItemStatus.OPEN, "Silver water bottle");
        Item unrelated = fixtures.item(carol, ItemType.FOUND, ItemStatus.OPEN, "Car keys");

        MatchRefreshService.RefreshSummary summary = refreshService.refreshAll();

        assertThat(summary.affectedItems()).isEqualTo(3);
        assertThat(candidates.findByLostItem_IdAndFoundItem_Id(lost.getId(), found.getId()))
                .isPresent();
        // Both sides published their complete snapshot even though the pair
        // was stored while only one endpoint's batch was open.
        assertThat(events.lastForItem(lost.getId()).candidates())
                .anyMatch(c -> c.foundItemId() == found.getId());
        assertThat(events.lastForItem(found.getId()).candidates())
                .anyMatch(c -> c.lostItemId() == lost.getId());
        assertThat(events.lastForItem(unrelated.getId()).candidates()).isEmpty();
    }
}
