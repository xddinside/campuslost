package com.lostfound.support;

import com.lostfound.model.*;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.PhotoRepository;
import com.lostfound.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final UserRepository users;
    private final ItemRepository items;
    private final ClaimRepository claims;
    private final PhotoRepository photos;
    private final PasswordEncoder encoder;

    public TestFixtures(UserRepository users, ItemRepository items,
                        ClaimRepository claims, PhotoRepository photos,
                        PasswordEncoder encoder) {
        this.users = users;
        this.items = items;
        this.claims = claims;
        this.photos = photos;
        this.encoder = encoder;
    }

    public void reset() {
        claims.deleteAll();
        items.clearPhotoReferences();
        photos.deleteAll();
        items.deleteAll();
        users.deleteAll();
    }

    public User user(String name, Role role) {
        int n = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setName(name);
        user.setEmail(name.toLowerCase().replace(' ', '.') + n + "@test.dev");
        user.setPasswordHash(encoder.encode("password123"));
        user.setPhone("555-" + String.format("%04d", n));
        user.setRole(role);
        return users.save(user);
    }

    public Item item(User reporter, ItemType type, ItemStatus status, String title) {
        Item item = new Item();
        item.setReporter(reporter);
        item.setType(type);
        item.setTitle(title);
        item.setDescription("A sufficiently detailed description for tests " + title);
        item.setCategory("Other");
        item.setLocation("Central Library");
        item.setDateOccurred(Instant.now().minus(2, ChronoUnit.DAYS));
        item.setStatus(status);
        return items.save(item);
    }

    public Claim claim(Item item, User claimant, ClaimStatus status) {
        Claim claim = new Claim();
        claim.setItem(item);
        claim.setClaimant(claimant);
        claim.setMessage("I believe this is mine; here is a detail only the owner would know.");
        claim.setStatus(status);
        return claims.save(claim);
    }
}
