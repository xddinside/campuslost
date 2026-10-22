package com.lostfound;

import com.lostfound.model.*;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.PhotoRepository;
import com.lostfound.repository.UserRepository;
import com.lostfound.security.JwtService;
import com.lostfound.support.CleanFlywayConfig;
import com.lostfound.support.TestFixtures;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(CleanFlywayConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiSecurityAndPrivacyTest {

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
    @Value("${app.jwt.secret}")
    String jwtSecret;

    TestFixtures fixtures;
    User alice;
    User bob;
    User admin;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
        bob = fixtures.user("Bob", Role.USER);
        admin = fixtures.user("Admin", Role.ADMIN);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generate(user);
    }

    @Test
    void privateRoutesAre401BeforeBroadPublicRules() throws Exception {
        for (String path : new String[]{"/api/auth/me", "/api/me/claims",
                "/api/items/1/claims", "/api/items/1/edit", "/api/items/1/matches",
                "/api/items/1/ownership-question", "/api/claims/1/contact"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void publicItemJsonNeverContainsContactsOrPrivateEvidence() throws Exception {
        Item item = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Blue bottle");
        item.setOwnershipQuestion("What sticker is under the lid?");
        items.save(item);
        fixtures.claim(item, bob, ClaimStatus.PENDING);

        MvcResult result = mvc.perform(get("/api/items/" + item.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reporterName").value("Alice"))
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.reporterEmail").doesNotExist())
                .andExpect(jsonPath("$.reporterPhone").doesNotExist())
                .andExpect(jsonPath("$.ownershipQuestion").doesNotExist())
                .andExpect(jsonPath("$.ownershipAnswer").doesNotExist())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("@test.dev")
                .doesNotContain("555-")
                .doesNotContain("sticker is under");
    }

    @Test
    void malformedEnumAndUnknownRecordUseContractErrors() throws Exception {
        mvc.perform(get("/api/items").param("status", "BANANA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/items/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void strangersCannotReadClaimsOrEditButAdminCan() throws Exception {
        Item item = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Keys");
        fixtures.claim(item, bob, ClaimStatus.PENDING);

        mvc.perform(get("/api/items/" + item.getId() + "/claims").header("Authorization", bearer(bob)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/items/" + item.getId() + "/edit").header("Authorization", bearer(bob)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/items/" + item.getId() + "/edit").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/items/" + item.getId() + "/claims").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].claimantName").value("Bob"))
                .andExpect(jsonPath("$[0].claimantEmail").doesNotExist());
    }

    @Test
    void contactIsParticipantOnlyAfterApprovalAndIsNoStore() throws Exception {
        Item item = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Wallet");
        Claim claim = fixtures.claim(item, bob, ClaimStatus.PENDING);

        mvc.perform(get("/api/claims/" + claim.getId() + "/contact")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/claims/" + claim.getId())
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(get("/api/claims/" + claim.getId() + "/contact")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.reporter.email").value(alice.getEmail()))
                .andExpect(jsonPath("$.claimant.email").value(bob.getEmail()));

        User stranger = fixtures.user("Stranger", Role.USER);
        mvc.perform(get("/api/claims/" + claim.getId() + "/contact")
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
    }

    @Test
    void photoUploadRequiresAuthRejectsFakeImagesAndHidesUnattachedFiles() throws Exception {
        byte[] png = pngBytes();
        mvc.perform(multipart("/api/photos")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png)))
                .andExpect(status().isUnauthorized());

        mvc.perform(multipart("/api/photos")
                        .file(new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes()))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHOTO"));

        MvcResult upload = mvc.perform(multipart("/api/photos")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url", containsString("/api/photos/")))
                .andReturn();
        JsonNode uploaded = objectMapper.readTree(upload.getResponse().getContentAsString());
        String photoId = uploaded.get("id").asString();

        mvc.perform(get("/api/photos/" + photoId))
                .andExpect(status().isNotFound());

        String itemJson = """
                {"type":"FOUND","title":"Bottle with photo","description":"A detailed description of the bottle.",
                 "category":"Other","location":"Gym","dateOccurred":"2026-01-02T10:00:00Z","photoId":"%s",
                 "ownershipQuestion":"What colour is the sticker under the lid?"}
                """.formatted(photoId);
        MvcResult created = mvc.perform(post("/api/items")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photoUrl").value("/api/photos/" + photoId))
                .andReturn();

        mvc.perform(get("/api/photos/" + photoId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // Another user cannot attach Alice's upload by guessing its ID.
        String bobItemJson = """
                {"type":"FOUND","title":"Borrowed photo","description":"A detailed description for this item.",
                 "category":"Other","location":"Gym","dateOccurred":"2026-01-02T10:00:00Z","photoId":"%s",
                 "ownershipQuestion":"What colour is the sticker under the lid?"}
                """.formatted(photoId);
        mvc.perform(post("/api/items")
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bobItemJson))
                .andExpect(status().isForbidden());
    }

    @Test
    void staleEditVersionIsRejectedAndPatchClearsNullableFields() throws Exception {
        Item item = fixtures.item(alice, ItemType.FOUND, ItemStatus.OPEN, "Umbrella");
        String path = "/api/items/" + item.getId();

        mvc.perform(patch(path)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"Blue umbrella\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(patch(path)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"Stale edit\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        mvc.perform(patch(path)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"ownershipQuestion\":null,\"photoId\":null}"))
                .andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejectedAndIdentityResponsesArePrivate() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String expired = Jwts.builder()
                .subject(String.valueOf(alice.getId()))
                .claim("role", alice.getRole().name())
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret)))
                .compact();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")));
    }

    @Test
    void loginIssuesTokenAndRejectsBadCredentials() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + alice.getEmail() + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + alice.getEmail() + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(alice.getEmail()));
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
