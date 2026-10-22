package com.lostfound.ownership;

import com.lostfound.dto.ClaimRequest;
import com.lostfound.dto.ClaimResponse;
import com.lostfound.dto.ItemRequest;
import com.lostfound.dto.ItemResponse;
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
import com.lostfound.service.ClaimService;
import com.lostfound.service.ItemService;
import com.lostfound.support.CleanFlywayConfig;
import com.lostfound.support.TestFixtures;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(CleanFlywayConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnershipApiPrivacyTest {

    private static final String QUESTION = "What is engraved inside the bottle lid?";
    private static final String BOB_ANSWER = "The initials B.M. are engraved inside the lid.";
    private static final String CAROL_ANSWER = "There is a small star scratched inside the lid.";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ItemRepository items;
    @Autowired ClaimRepository claims;
    @Autowired PhotoRepository photos;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;
    @Autowired ItemService itemService;
    @Autowired ClaimService claimService;

    TestFixtures fixtures;
    User alice;
    User bob;
    User carol;
    User admin;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
        bob = fixtures.user("Bob", Role.USER);
        carol = fixtures.user("Carol", Role.USER);
        admin = fixtures.user("Admin", Role.ADMIN);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generate(user);
    }

    @Test
    void promptIsPrivateToSignedInVisitorsButPublicJsonNeverRevealsIt() throws Exception {
        Item found = found(QUESTION);
        mvc.perform(get("/api/items/" + found.getId()).header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownershipQuestion").doesNotExist())
                .andExpect(jsonPath("$.ownershipAnswer").doesNotExist())
                .andExpect(jsonPath("$.reporterEmail").doesNotExist());

        mvc.perform(get("/api/items/" + found.getId() + "/ownership-question"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/items/" + found.getId() + "/ownership-question")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.itemId").value(found.getId()))
                .andExpect(jsonPath("$.question").value(QUESTION))
                .andExpect(jsonPath("$.required").value(true));

        Item legacy = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Legacy found");
        mvc.perform(get("/api/items/" + legacy.getId() + "/ownership-question")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value(nullValue()))
                .andExpect(jsonPath("$.required").value(false));

        Item lost = lost();
        mvc.perform(get("/api/items/" + lost.getId() + "/ownership-question")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ITEM_TYPE"));
    }

    @Test
    void questionEndpointClosesWithTheReportButStaysReadableForReview() throws Exception {
        Item found = found(QUESTION);
        ClaimResponse claim = claimService.create(bob, found.getId(),
                new ClaimRequest("This is mine, here is the private detail.", null, BOB_ANSWER));
        claimService.decide(alice, claim.id(),
                new com.lostfound.dto.ClaimDecisionRequest(ClaimStatus.APPROVED));

        mvc.perform(get("/api/items/" + found.getId() + "/ownership-question")
                        .header("Authorization", bearer(carol)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_OPEN"));

        mvc.perform(get("/api/items/" + found.getId() + "/ownership-question")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value(QUESTION));
        mvc.perform(get("/api/items/" + found.getId() + "/ownership-question")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value(QUESTION));

        claimService.release(alice, claim.id(), "Test cleanup before closing the report.");
        itemService.updateStatus(alice, found.getId(), ItemStatus.CLOSED);
        mvc.perform(get("/api/items/" + found.getId() + "/ownership-question")
                        .header("Authorization", bearer(carol)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/items/" + found.getId() + "/ownership-question")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
    }

    @Test
    void evidenceIsOnlyReadableByThatClaimantReporterOrAdmin() throws Exception {
        Item found = found(QUESTION);
        ClaimResponse bobClaim = claimService.create(bob, found.getId(),
                new ClaimRequest("This is mine, here is the private detail.", null, BOB_ANSWER));
        ClaimResponse carolClaim = claimService.create(carol, found.getId(),
                new ClaimRequest("I think this is mine as well.", null, CAROL_ANSWER));

        mvc.perform(get("/api/claims/" + bobClaim.id() + "/evidence")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.claimId").value(bobClaim.id()))
                .andExpect(jsonPath("$.question").value(QUESTION))
                .andExpect(jsonPath("$.answer").value(BOB_ANSWER));

        mvc.perform(get("/api/claims/" + bobClaim.id() + "/evidence")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(BOB_ANSWER));
        mvc.perform(get("/api/claims/" + bobClaim.id() + "/evidence")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(BOB_ANSWER));

        mvc.perform(get("/api/claims/" + bobClaim.id() + "/evidence")
                        .header("Authorization", bearer(carol)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/claims/" + bobClaim.id() + "/evidence"))
                .andExpect(status().isUnauthorized());

        // The claimant still reads their own separate answer, never another's.
        mvc.perform(get("/api/claims/" + carolClaim.id() + "/evidence")
                        .header("Authorization", bearer(carol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(CAROL_ANSWER));
        mvc.perform(get("/api/claims/" + carolClaim.id() + "/evidence")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isForbidden());

        // Rejected submissions remain private history for their own participants.
        claimService.decide(alice, bobClaim.id(),
                new com.lostfound.dto.ClaimDecisionRequest(ClaimStatus.REJECTED));
        mvc.perform(get("/api/claims/" + bobClaim.id() + "/evidence")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(BOB_ANSWER));
    }

    @Test
    void claimResponsesAndPublicFeedsNeverCarryEvidence() throws Exception {
        Item found = found(QUESTION);
        ClaimResponse claim = claimService.create(bob, found.getId(),
                new ClaimRequest("This is mine, here is the private detail.", null, BOB_ANSWER));

        MvcResult claimView = mvc.perform(get("/api/items/" + found.getId() + "/claims")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].claimantName").value("Bob"))
                .andExpect(jsonPath("$[0].ownershipAnswer").doesNotExist())
                .andExpect(jsonPath("$[0].ownershipQuestion").doesNotExist())
                .andReturn();
        assertThat(claimView.getResponse().getContentAsString())
                .doesNotContain(QUESTION)
                .doesNotContain(BOB_ANSWER);

        MvcResult myClaims = mvc.perform(get("/api/me/claims")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(claim.id()))
                .andReturn();
        assertThat(myClaims.getResponse().getContentAsString())
                .doesNotContain(QUESTION)
                .doesNotContain(BOB_ANSWER);

        MvcResult publicFeed = mvc.perform(get("/api/items"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(publicFeed.getResponse().getContentAsString())
                .doesNotContain(QUESTION)
                .doesNotContain(BOB_ANSWER);
    }

    @Test
    void lostResponseEvidenceIsEmptyRatherThanLeakingAQuestion() throws Exception {
        Item lost = lost();
        ClaimResponse response = claimService.create(bob, lost.getId(),
                new ClaimRequest("I saw it near the library desk.", null, null));

        mvc.perform(get("/api/claims/" + response.id() + "/evidence")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value(nullValue()))
                .andExpect(jsonPath("$.answer").value(nullValue()));
    }

    private Item found(String question) {
        ItemResponse response = itemService.create(alice, new ItemRequest(
                ItemType.FOUND,
                "Blue bottle",
                "Found after the evening class. A few identifying details were withheld.",
                "Other",
                "University Gym",
                Instant.now().minusSeconds(3600),
                question,
                null));
        return items.findById(response.id()).orElseThrow();
    }

    private Item lost() {
        ItemResponse response = itemService.create(alice, new ItemRequest(
                ItemType.LOST,
                "Lost student card",
                "Lost between the library and the bus stop.",
                "ID & Documents",
                "Central Library",
                Instant.now().minusSeconds(7200),
                null,
                null));
        return items.findById(response.id()).orElseThrow();
    }
}
