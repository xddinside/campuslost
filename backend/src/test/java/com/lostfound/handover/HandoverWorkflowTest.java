package com.lostfound.handover;

import com.lostfound.contract.ClaimChangedEvent;
import com.lostfound.contract.ItemChangedEvent;
import com.lostfound.dto.ClaimRequest;
import com.lostfound.exception.ApiException;
import com.lostfound.model.*;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.PhotoRepository;
import com.lostfound.repository.UserRepository;
import com.lostfound.security.JwtService;
import com.lostfound.service.ItemService;
import com.lostfound.support.CleanFlywayConfig;
import com.lostfound.support.TestFixtures;
import com.lostfound.workflow.ClaimWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import({
        CleanFlywayConfig.class,
        HandoverWorkflowTest.TestBeans.class,
        HandoverWorkflowTest.EventConfig.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HandoverWorkflowTest {

    static final TestClock CLOCK = new TestClock(Instant.now());
    static final SequenceCodes CODES = new SequenceCodes();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:mariadb://localhost:3306/laf_handover_test"
                        + "?useUnicode=true&characterEncoding=utf8");
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        HandoverCodeGenerator testCodes() {
            return CODES;
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class EventConfig {
        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    static class EventRecorder {
        final List<ClaimChangedEvent> claimEvents = new CopyOnWriteArrayList<>();
        final List<ItemChangedEvent> itemEvents = new CopyOnWriteArrayList<>();
        volatile boolean failCompletion;

        @EventListener
        void onClaim(ClaimChangedEvent event) {
            if (failCompletion && event.status() == ClaimStatus.COMPLETED && event.handoverVerified()) {
                throw new IllegalStateException("simulated failure while completing the handover");
            }
            claimEvents.add(event);
        }

        @EventListener
        void onItem(ItemChangedEvent event) {
            itemEvents.add(event);
        }

        void reset() {
            claimEvents.clear();
            itemEvents.clear();
            failCompletion = false;
        }

        long verifiedCompletions() {
            return claimEvents.stream()
                    .filter(e -> e.status() == ClaimStatus.COMPLETED && e.handoverVerified())
                    .count();
        }
    }

    static class TestClock extends Clock {
        private volatile Instant current;

        TestClock(Instant current) {
            this.current = current;
        }

        void set(Instant instant) {
            this.current = instant;
        }

        void advance(Duration duration) {
            this.current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    static class SequenceCodes implements HandoverCodeGenerator {
        private final AtomicInteger counter = new AtomicInteger();
        private volatile String fixed;

        void use(String code) {
            fixed = code;
        }

        void reset() {
            fixed = null;
            counter.set(0);
        }

        @Override
        public String nextCode() {
            String pinned = fixed;
            if (pinned != null) {
                return pinned;
            }
            return String.format("%06d", 100000 + counter.incrementAndGet());
        }
    }

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
    HandoverChallengeRepository challenges;
    @Autowired
    HandoverService handover;
    @Autowired
    ClaimWorkflow workflow;
    @Autowired
    ItemService itemService;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JwtService jwtService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    EventRecorder events;

    TestFixtures fixtures;
    User alice;
    User bob;
    User admin;
    User stranger;

    @BeforeEach
    void setUp() {
        challenges.deleteAll();
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
        bob = fixtures.user("Bob", Role.USER);
        admin = fixtures.user("Admin", Role.ADMIN);
        stranger = fixtures.user("Stranger", Role.USER);
        CLOCK.set(Instant.now());
        CODES.reset();
        events.reset();
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generate(user);
    }

    private ApiException apiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    private Claim approvedFoundClaim(Item found, User claimant, Item linkedLostReport) {
        Claim claim = linkedLostReport == null
                ? fixtures.claim(found, claimant, ClaimStatus.PENDING)
                : workflow.create(claimant, found.getId(),
                        new ClaimRequest("This is mine; here is a private detail.",
                                linkedLostReport.getId(), null));
        workflow.decide(found.getReporter(), claim.getId(), ClaimStatus.APPROVED);
        return claim;
    }

    private Claim approvedLinkedPair() {
        Item lost = fixtures.item(bob, ItemType.LOST, ItemStatus.OPEN, "Lost wallet");
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found wallet");
        return approvedFoundClaim(found, bob, lost);
    }

    @Test
    void approvalReservesThenVerifiedPickupResolvesBothReportsWithEqualTimestamps() {
        Item lost = fixtures.item(bob, ItemType.LOST, ItemStatus.OPEN, "Lost wallet");
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found wallet");
        Claim claim = approvedFoundClaim(found, bob, lost);

        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(items.findById(lost.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(items.findById(found.getId()).orElseThrow().getResolvedAt()).isNull();
        assertThat(items.findById(lost.getId()).orElseThrow().getResolvedAt()).isNull();
        assertThat(claims.findById(claim.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claims.findById(claim.getId()).orElseThrow().getCompletedAt()).isNull();

        HandoverCodeResponse issued = handover.issue(bob, claim.getId());
        assertThat(issued.code()).matches("\\d{6}");
        assertThat(issued.expiresAt()).isEqualTo(CLOCK.instant().plus(Duration.ofMinutes(10)));

        HandoverChallenge stored = challenges.findByClaimId(claim.getId()).orElseThrow();
        assertThat(stored.getCodeDigest()).hasSize(64).isNotEqualTo(issued.code());
        assertThat(stored.getGeneration()).isEqualTo(1);
        assertThat(stored.getFailedAttempts()).isZero();

        HandoverVerifyResponse result = handover.verify(alice, claim.getId(), issued.code());

        Claim completed = claims.findById(claim.getId()).orElseThrow();
        Item resolvedFound = items.findById(found.getId()).orElseThrow();
        Item resolvedLost = items.findById(lost.getId()).orElseThrow();
        HandoverChallenge verified = challenges.findByClaimId(claim.getId()).orElseThrow();

        assertThat(result.claim().status()).isEqualTo(ClaimStatus.COMPLETED);
        assertThat(completed.getStatus()).isEqualTo(ClaimStatus.COMPLETED);
        assertThat(completed.isHandoverVerified()).isTrue();
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(resolvedFound.getStatus()).isEqualTo(ItemStatus.RESOLVED);
        assertThat(resolvedLost.getStatus()).isEqualTo(ItemStatus.RESOLVED);
        assertThat(resolvedFound.getResolvedAt()).isEqualTo(completed.getCompletedAt());
        assertThat(resolvedLost.getResolvedAt()).isEqualTo(completed.getCompletedAt());
        assertThat(verified.getVerifiedAt()).isEqualTo(completed.getCompletedAt());
        assertThat(verified.getVerifiedBy().getId()).isEqualTo(alice.getId());
        assertThat(result.verifiedAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(completed.getCompletedAt());
        assertThat(events.claimEvents).anySatisfy(event -> {
            assertThat(event.claimId()).isEqualTo(claim.getId());
            assertThat(event.status()).isEqualTo(ClaimStatus.COMPLETED);
            assertThat(event.handoverVerified()).isTrue();
        });
        assertThat(events.itemEvents).extracting(ItemChangedEvent::itemId)
                .contains(found.getId(), lost.getId());
    }

    @Test
    void wrongCodePersistsAttemptsAndLocksAfterFive() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found charger");
        Claim claim = approvedFoundClaim(found, bob, null);
        CODES.use("123456");
        handover.issue(bob, claim.getId());

        for (int attempt = 1; attempt <= 4; attempt++) {
            ApiException error = apiError(() -> handover.verify(alice, claim.getId(), "999999"));
            assertThat(error.getStatus().value()).isEqualTo(400);
            assertThat(error.getCode()).isEqualTo("INVALID_CODE");
            assertThat(challenges.findByClaimId(claim.getId()).orElseThrow().getFailedAttempts())
                    .isEqualTo(attempt);
        }

        HandoverRateLimitException locked = (HandoverRateLimitException)
                apiError(() -> handover.verify(alice, claim.getId(), "999999"));
        assertThat(locked.getStatus().value()).isEqualTo(429);
        assertThat(locked.getCode()).isEqualTo("CODE_LOCKED");
        assertThat(locked.getRetryAfterSeconds()).isPositive();
        assertThat(challenges.findByClaimId(claim.getId()).orElseThrow().getFailedAttempts()).isEqualTo(5);

        HandoverStatusResponse status = handover.status(alice, claim.getId());
        assertThat(status.state()).isEqualTo(HandoverState.LOCKED);
        assertThat(status.attemptsRemaining()).isZero();

        ApiException stillLocked = apiError(() -> handover.verify(alice, claim.getId(), "123456"));
        assertThat(stillLocked.getStatus().value()).isEqualTo(429);
        assertThat(stillLocked.getCode()).isEqualTo("CODE_LOCKED");
    }

    @Test
    void fiveSimultaneousWrongAttemptsAllPersist() throws Exception {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found headphones");
        Claim claim = approvedFoundClaim(found, bob, null);
        CODES.use("123456");
        handover.issue(bob, claim.getId());

        List<String> outcomes = runTogether(5, () -> {
            try {
                handover.verify(alice, claim.getId(), "999999");
                return "OK";
            } catch (ApiException ex) {
                return ex.getStatus().value() + ":" + ex.getCode();
            }
        });

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome)
                .isIn("400:INVALID_CODE", "429:CODE_LOCKED"));
        assertThat(challenges.findByClaimId(claim.getId()).orElseThrow().getFailedAttempts()).isEqualTo(5);
        assertThat(handover.status(alice, claim.getId()).state()).isEqualTo(HandoverState.LOCKED);
    }

    @Test
    void leadingZerosSurviveIssuanceAndVerification() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found calculator");
        Claim claim = approvedFoundClaim(found, bob, null);
        CODES.use("000042");

        HandoverCodeResponse issued = handover.issue(bob, claim.getId());
        assertThat(issued.code()).isEqualTo("000042");

        HandoverVerifyResponse result = handover.verify(alice, claim.getId(), "000042");
        assertThat(result.claim().status()).isEqualTo(ClaimStatus.COMPLETED);
    }

    @Test
    void cooldownAndRegenerationInvalidateThePreviousCode() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found scarf");
        Claim claim = approvedFoundClaim(found, bob, null);

        HandoverCodeResponse first = handover.issue(bob, claim.getId());
        HandoverRateLimitException cooldown = (HandoverRateLimitException)
                apiError(() -> handover.issue(bob, claim.getId()));
        assertThat(cooldown.getStatus().value()).isEqualTo(429);
        assertThat(cooldown.getCode()).isEqualTo("CODE_COOLDOWN");
        assertThat(cooldown.getRetryAfterSeconds()).isBetween(1L, 60L);

        CLOCK.advance(Duration.ofSeconds(61));
        HandoverCodeResponse second = handover.issue(bob, claim.getId());
        assertThat(second.code()).isNotEqualTo(first.code());

        ApiException oldCode = apiError(() -> handover.verify(alice, claim.getId(), first.code()));
        assertThat(oldCode.getCode()).isEqualTo("INVALID_CODE");
        assertThat(challenges.findByClaimId(claim.getId()).orElseThrow().getGeneration()).isEqualTo(2);

        HandoverVerifyResponse result = handover.verify(alice, claim.getId(), second.code());
        assertThat(result.claim().status()).isEqualTo(ClaimStatus.COMPLETED);
    }

    @Test
    void expiredCodeFailsClearlyAndRegenerationRecovers() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found umbrella");
        Claim claim = approvedFoundClaim(found, bob, null);
        HandoverCodeResponse issued = handover.issue(bob, claim.getId());

        CLOCK.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(handover.status(alice, claim.getId()).state()).isEqualTo(HandoverState.EXPIRED);

        ApiException expired = apiError(() -> handover.verify(alice, claim.getId(), issued.code()));
        assertThat(expired.getStatus().value()).isEqualTo(400);
        assertThat(expired.getCode()).isEqualTo("CODE_EXPIRED");
        assertThat(challenges.findByClaimId(claim.getId()).orElseThrow().getFailedAttempts()).isZero();

        CLOCK.advance(Duration.ofSeconds(61));
        HandoverCodeResponse replacement = handover.issue(bob, claim.getId());
        assertThat(handover.verify(alice, claim.getId(), replacement.code()).claim().status())
                .isEqualTo(ClaimStatus.COMPLETED);
    }

    @Test
    void releaseInvalidatesChallengeAndReopensBothReports() {
        Item lost = fixtures.item(bob, ItemType.LOST, ItemStatus.OPEN, "Lost keys");
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found keys");
        Claim claim = approvedFoundClaim(found, bob, lost);
        HandoverCodeResponse issued = handover.issue(bob, claim.getId());
        Instant beforeRelease = CLOCK.instant();

        var released = handover.release(alice, claim.getId(), "Owner did not arrive");

        assertThat(released.status()).isEqualTo(ClaimStatus.CANCELLED);
        assertThat(claims.findById(claim.getId()).orElseThrow().getReleaseReason())
                .isEqualTo("Owner did not arrive");
        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.OPEN);
        assertThat(items.findById(lost.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.OPEN);
        assertThat(items.findById(found.getId()).orElseThrow().getResolvedAt()).isNull();
        assertThat(items.findById(lost.getId()).orElseThrow().getResolvedAt()).isNull();

        HandoverChallenge invalidated = challenges.findByClaimId(claim.getId()).orElseThrow();
        assertThat(invalidated.getVerifiedAt()).isNull();
        assertThat(invalidated.getExpiresAt()).isBeforeOrEqualTo(beforeRelease);
        assertThat(invalidated.getGeneration()).isEqualTo(2);

        assertThat(apiError(() -> handover.status(bob, claim.getId())).getCode())
                .isEqualTo("HANDOVER_NOT_READY");
        assertThat(apiError(() -> handover.verify(alice, claim.getId(), issued.code())).getStatus().value())
                .isEqualTo(409);
        assertThat(apiError(() -> handover.issue(bob, claim.getId())).getStatus().value())
                .isEqualTo(409);
    }

    @Test
    void rolesAreEnforcedForEveryHandoverAction() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found glasses");
        Claim claim = approvedFoundClaim(found, bob, null);
        CODES.use("654321");
        HandoverCodeResponse issued = handover.issue(bob, claim.getId());

        assertThat(apiError(() -> handover.issue(alice, claim.getId())).getStatus().value()).isEqualTo(403);
        assertThat(apiError(() -> handover.issue(admin, claim.getId())).getStatus().value()).isEqualTo(403);
        assertThat(apiError(() -> handover.status(stranger, claim.getId())).getStatus().value()).isEqualTo(403);
        assertThat(apiError(() -> handover.verify(bob, claim.getId(), issued.code())).getStatus().value())
                .isEqualTo(403);
        assertThat(apiError(() -> handover.release(stranger, claim.getId(), "no")).getStatus().value())
                .isEqualTo(403);

        assertThat(handover.status(alice, claim.getId()).state()).isEqualTo(HandoverState.READY);
        assertThat(handover.status(admin, claim.getId()).state()).isEqualTo(HandoverState.READY);
        assertThat(handover.verify(admin, claim.getId(), issued.code()).claim().status())
                .isEqualTo(ClaimStatus.COMPLETED);
    }

    @Test
    void adminClaimantCannotVerifyOrReleaseTheirOwnPickup() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found laptop");
        Claim claim = approvedFoundClaim(found, admin, null);
        CODES.use("246810");
        HandoverCodeResponse issued = handover.issue(admin, claim.getId());

        // Administrator privileges never let someone verify their own claim.
        ApiException selfVerify = apiError(() -> handover.verify(admin, claim.getId(), issued.code()));
        assertThat(selfVerify.getStatus().value()).isEqualTo(403);

        ApiException selfRelease = apiError(() ->
                handover.release(admin, claim.getId(), "I changed my mind"));
        assertThat(selfRelease.getStatus().value()).isEqualTo(403);

        // The reporter's legitimate verify still completes the same claim.
        assertThat(handover.verify(alice, claim.getId(), issued.code()).claim().status())
                .isEqualTo(ClaimStatus.COMPLETED);
    }

    @Test
    void twoSimultaneousCorrectVerifiesProduceOneTransitionAndOneEvent() throws Exception {
        Item lost = fixtures.item(bob, ItemType.LOST, ItemStatus.OPEN, "Lost bag");
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found bag");
        Claim claim = approvedFoundClaim(found, bob, lost);
        HandoverCodeResponse issued = handover.issue(bob, claim.getId());

        List<HandoverVerifyResponse> results = runTogether(2,
                () -> handover.verify(alice, claim.getId(), issued.code()));

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.claim().status()).isEqualTo(ClaimStatus.COMPLETED);
            assertThat(result.verifiedAt()).isNotNull();
        });
        assertThat(results.get(0).verifiedAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(results.get(1).verifiedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(events.verifiedCompletions()).isEqualTo(1);
        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESOLVED);
        assertThat(items.findById(lost.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESOLVED);
    }

    @Test
    void verifyVersusReleaseRaceLeavesAConsistentState() throws Exception {
        Item lost = fixtures.item(bob, ItemType.LOST, ItemStatus.OPEN, "Lost coat");
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found coat");
        Claim claim = approvedFoundClaim(found, bob, lost);
        HandoverCodeResponse issued = handover.issue(bob, claim.getId());

        List<String> outcomes = runTogether(2, new Callable<String>() {
            private final AtomicInteger picks = new AtomicInteger();

            @Override
            public String call() {
                if (picks.getAndIncrement() == 0) {
                    try {
                        handover.verify(alice, claim.getId(), issued.code());
                        return "VERIFIED";
                    } catch (ApiException ex) {
                        return "VERIFY_" + ex.getStatus().value();
                    }
                }
                try {
                    handover.release(alice, claim.getId(), "No-show");
                    return "RELEASED";
                } catch (ApiException ex) {
                    return "RELEASE_" + ex.getStatus().value();
                }
            }
        });

        assertThat(outcomes).containsAnyOf("VERIFIED", "RELEASED");
        Claim finalClaim = claims.findById(claim.getId()).orElseThrow();
        Item finalFound = items.findById(found.getId()).orElseThrow();
        Item finalLost = items.findById(lost.getId()).orElseThrow();

        if (finalClaim.getStatus() == ClaimStatus.COMPLETED) {
            assertThat(finalClaim.isHandoverVerified()).isTrue();
            assertThat(finalFound.getStatus()).isEqualTo(ItemStatus.RESOLVED);
            assertThat(finalLost.getStatus()).isEqualTo(ItemStatus.RESOLVED);
            assertThat(finalFound.getResolvedAt()).isEqualTo(finalClaim.getCompletedAt());
            assertThat(finalLost.getResolvedAt()).isEqualTo(finalClaim.getCompletedAt());
        } else {
            assertThat(finalClaim.getStatus()).isEqualTo(ClaimStatus.CANCELLED);
            assertThat(finalFound.getStatus()).isEqualTo(ItemStatus.OPEN);
            assertThat(finalLost.getStatus()).isEqualTo(ItemStatus.OPEN);
            assertThat(finalFound.getResolvedAt()).isNull();
            assertThat(finalLost.getResolvedAt()).isNull();
            assertThat(challenges.findByClaimId(claim.getId()).orElseThrow().getVerifiedAt()).isNull();
        }
    }

    @Test
    void failureDuringCompletionRollsBackBothReportsAndTheChallenge() {
        Item lost = fixtures.item(bob, ItemType.LOST, ItemStatus.OPEN, "Lost tablet");
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found tablet");
        Claim claim = approvedFoundClaim(found, bob, lost);
        HandoverCodeResponse issued = handover.issue(bob, claim.getId());

        events.failCompletion = true;
        Throwable thrown = catchThrowable(() -> handover.verify(alice, claim.getId(), issued.code()));
        events.failCompletion = false;
        assertThat(thrown).isInstanceOf(IllegalStateException.class);

        Claim storedClaim = claims.findById(claim.getId()).orElseThrow();
        assertThat(storedClaim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(storedClaim.getCompletedAt()).isNull();
        assertThat(storedClaim.isHandoverVerified()).isFalse();
        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(items.findById(lost.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(items.findById(found.getId()).orElseThrow().getResolvedAt()).isNull();
        assertThat(items.findById(lost.getId()).orElseThrow().getResolvedAt()).isNull();
        assertThat(challenges.findByClaimId(claim.getId()).orElseThrow().getVerifiedAt()).isNull();
        assertThat(events.verifiedCompletions()).isZero();

        // The same code still works after the failed transaction.
        assertThat(handover.verify(alice, claim.getId(), issued.code()).claim().status())
                .isEqualTo(ClaimStatus.COMPLETED);
    }

    @Test
    void ordinaryStatusAndDecisionEndpointsCannotCompleteFoundHandover() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found watch");
        Claim claim = approvedFoundClaim(found, bob, null);

        ApiException decision = apiError(() ->
                workflow.decide(alice, claim.getId(), ClaimStatus.COMPLETED));
        assertThat(decision.getStatus().value()).isEqualTo(400);
        assertThat(decision.getCode()).isEqualTo("INVALID_DECISION");

        ApiException status = apiError(() ->
                itemService.updateStatus(alice, found.getId(), ItemStatus.RESOLVED));
        assertThat(status.getStatus().value()).isEqualTo(409);
        assertThat(status.getCode()).isEqualTo("ITEM_RESERVED");

        assertThat(claims.findById(claim.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(items.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(items.findById(found.getId()).orElseThrow().getResolvedAt()).isNull();
    }

    @Test
    void lostResponsesAndPendingClaimsHaveNoHandover() {
        Item lost = fixtures.item(alice, ItemType.LOST, ItemStatus.OPEN, "Lost phone");
        Claim response = fixtures.claim(lost, bob, ClaimStatus.PENDING);
        workflow.decide(alice, response.getId(), ClaimStatus.APPROVED);

        assertThat(apiError(() -> handover.status(bob, response.getId())).getCode())
                .isEqualTo("HANDOVER_NOT_APPLICABLE");
        assertThat(apiError(() -> handover.issue(bob, response.getId())).getCode())
                .isEqualTo("HANDOVER_NOT_READY");

        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found mug");
        Claim pending = fixtures.claim(found, bob, ClaimStatus.PENDING);
        assertThat(apiError(() -> handover.status(bob, pending.getId())).getCode())
                .isEqualTo("HANDOVER_NOT_READY");
        assertThat(apiError(() -> handover.issue(bob, pending.getId())).getCode())
                .isEqualTo("HANDOVER_NOT_READY");
    }

    @Test
    void legacyCompletedClaimHasNoVerifiedPickup() {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.RESOLVED, "Legacy wallet");
        Claim legacy = fixtures.claim(found, bob, ClaimStatus.COMPLETED);
        legacy.setCompletedAt(Instant.now().minus(Duration.ofDays(3)));
        legacy.setHandoverVerified(false);
        claims.save(legacy);

        HandoverStatusResponse status = handover.status(alice, legacy.getId());
        assertThat(status.state()).isEqualTo(HandoverState.COMPLETED);
        assertThat(status.verifiedAt()).isNull();
        assertThat(status.attemptsRemaining()).isNull();
        assertThat(challenges.findByClaimId(legacy.getId())).isEmpty();
        assertThat(apiError(() -> handover.verify(alice, legacy.getId(), "123456")).getStatus().value())
                .isEqualTo(409);
    }

    @Test
    void statusAndIssuanceResponsesNeverLeakTheCodeOrDigest() throws Exception {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found notebook");
        Claim claim = approvedFoundClaim(found, bob, null);
        CODES.use("864209");

        MvcResult issuance = mvc.perform(post("/api/claims/" + claim.getId() + "/handover/code")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.code").value("864209"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn();
        assertThat(issuance.getResponse().getContentAsString()).doesNotContain("codeDigest");

        String digest = challenges.findByClaimId(claim.getId()).orElseThrow().getCodeDigest();
        MvcResult status = mvc.perform(get("/api/claims/" + claim.getId() + "/handover")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.attemptsRemaining").value(5))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.codeDigest").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn();
        assertThat(status.getResponse().getContentAsString())
                .doesNotContain("864209")
                .doesNotContain(digest);
    }

    @Test
    void cooldownReturns429WithRetryAfterHeader() throws Exception {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found lamp");
        Claim claim = approvedFoundClaim(found, bob, null);

        mvc.perform(post("/api/claims/" + claim.getId() + "/handover/code")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/claims/" + claim.getId() + "/handover/code")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code").value("CODE_COOLDOWN"));
    }

    @Test
    void malformedVerifyBodyIsAControlled400() throws Exception {
        Item found = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Found pen");
        Claim claim = approvedFoundClaim(found, bob, null);
        handover.issue(bob, claim.getId());

        mvc.perform(post("/api/claims/" + claim.getId() + "/handover/verify")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"12ab\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.code").exists());
    }

    private <T> List<T> runTogether(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
