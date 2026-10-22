package com.lostfound.photo;

import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import com.lostfound.model.Role;
import com.lostfound.model.User;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.PhotoRepository;
import com.lostfound.repository.PhotoUploadRepository;
import com.lostfound.repository.UserRepository;
import com.lostfound.security.JwtService;
import com.lostfound.support.CleanFlywayConfig;
import com.lostfound.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Upload quota behaviour: the ledger makes the allowance durable, and the
 * per-uploader lock makes concurrent requests honour it exactly.
 */
@SpringBootTest(properties = "app.photos.max-uploads-per-day=2")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(CleanFlywayConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhotoQuotaTest {

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
    PhotoUploadRepository photoUploads;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JwtService jwtService;

    TestFixtures fixtures;
    User alice;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures(users, items, claims, photos, encoder);
        fixtures.reset();
        alice = fixtures.user("Alice", Role.USER);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generate(user);
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void quotaIsDurableAndSurvivesPhotoDeletion() throws Exception {
        byte[] png = pngBytes();
        mvc.perform(MockMvcRequestBuilders.multipart("/api/photos")
                        .file(new MockMultipartFile("file", "a.png", "image/png", png))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isCreated());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/photos")
                        .file(new MockMultipartFile("file", "b.png", "image/png", png))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isCreated());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/photos")
                        .file(new MockMultipartFile("file", "c.png", "image/png", png))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isTooManyRequests());

        // Deleting the photo rows must not hand the allowance back.
        photos.deleteAll();
        mvc.perform(MockMvcRequestBuilders.multipart("/api/photos")
                        .file(new MockMultipartFile("file", "d.png", "image/png", png))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("PHOTO_LIMIT"));
        assertThat(photoUploads.count()).isEqualTo(2);
    }

    @Test
    void concurrentUploadsCannotExceedTheQuota() throws Exception {
        // One slot left; two concurrent uploads — exactly one must win.
        byte[] png = pngBytes();
        mvc.perform(MockMvcRequestBuilders.multipart("/api/photos")
                        .file(new MockMultipartFile("file", "first.png", "image/png", png))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isCreated());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        CountDownLatch barrier = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) {
                byte[] body = png;
                String name = "race" + i + ".png";
                pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    statuses.add(mvc.perform(MockMvcRequestBuilders.multipart("/api/photos")
                                    .file(new MockMultipartFile("file", name, "image/png", body))
                                    .header("Authorization", bearer(alice)))
                            .andReturn().getResponse().getStatus());
                    return null;
                });
            }
            barrier.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(1);
    }
}
