package com.lostfound.config;

import com.lostfound.model.*;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Repeatable local demo data. It only runs under the explicit demo profile and
 * only when the database has no users, so it can never overwrite real records.
 */
@Component
@Profile("demo")
@ConditionalOnProperty(name = "app.demo.seed", havingValue = "true")
public class SeedDataRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final ClaimRepository claimRepository;
    private final PasswordEncoder passwordEncoder;

    public SeedDataRunner(UserRepository userRepository,
                          ItemRepository itemRepository,
                          ClaimRepository claimRepository,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.claimRepository = claimRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }
        log.info("Seeding CampusLost demo data (profile=demo)...");

        User admin = user("Admin", "admin@lostfound.dev", "admin123", "555-0100", Role.ADMIN);
        User alice = user("Alice Chen", "alice@demo.dev", "demo123", "555-0101", Role.USER);
        User bob = user("Bob Martins", "bob@demo.dev", "demo123", "555-0102", Role.USER);
        User carol = user("Carol Nguyen", "carol@demo.dev", "demo123", "555-0103", Role.USER);
        User dave = user("Dave Wilson", "dave@demo.dev", "demo123", "555-0104", Role.USER);

        Instant now = Instant.now();

        Item i1 = item(alice, ItemType.LOST, "Black MacBook Pro 14\" with stickers",
                "Left it in the study hall on the second floor. It has a Hello Kitty sticker on the lid and a blue case.",
                "Electronics", "Central Library", now.minus(2, ChronoUnit.DAYS), ItemStatus.OPEN, null);
        Item i2 = item(bob, ItemType.FOUND, "Water bottle (dark blue, Hydro Flask)",
                "Found on the gym benches after 6pm class. Still full of water.",
                "Other", "University Gym", now.minus(1, ChronoUnit.DAYS), ItemStatus.OPEN, "Describe any stickers or scratches on the bottle.");
        Item i3 = item(carol, ItemType.LOST, "Student ID card — name 'M. Fernandez'",
                "Lost somewhere between the Student Union and the bus stop.",
                "ID & Documents", "Student Union", now.minus(3, ChronoUnit.DAYS), ItemStatus.OPEN, null);
        Item i4 = item(dave, ItemType.FOUND, "House keys on a red carabiner",
                "Found on a bench near the food court.",
                "Keys", "Cafeteria", now.minus(5, ChronoUnit.DAYS), ItemStatus.OPEN, "How many keys are on the ring and what else hangs from the carabiner?");
        Item i5 = item(alice, ItemType.FOUND, "iPhone 15 with clear case",
                "Handed in at the front desk — it was left in the lecture hall, locked, battery at 40%.",
                "Electronics", "Science Building", now.minus(6, ChronoUnit.DAYS), ItemStatus.RESERVED, "Describe the lock screen wallpaper without unlocking it.");
        Item i6 = item(bob, ItemType.LOST, "Blue umbrella with white polka dots",
                "Left it near the entrance of the Student Union during the rain on Tuesday.",
                "Other", "Student Union", now.minus(4, ChronoUnit.DAYS), ItemStatus.OPEN, null);
        Item i7 = item(carol, ItemType.FOUND, "Black hoodie, brand 'Uniqlo', size M",
                "Found folded on a chair in the media lab. No name inside.",
                "Clothing", "Media Lab", now.minus(7, ChronoUnit.DAYS), ItemStatus.OPEN, "Describe any damage, marks, or contents of the pockets.");
        Item i8 = item(dave, ItemType.LOST, "Textbook: Organic Chemistry (3rd edition)",
                "Probably left in the library after group study. Has 'D. Wong' written inside.",
                "Books", "Central Library", now.minus(8, ChronoUnit.DAYS), ItemStatus.OPEN, null);
        Item i9 = item(alice, ItemType.FOUND, "Brown leather wallet (empty)",
                "Found in the parking lot. It was empty — handed to security.",
                "Wallet & Purses", "Parking Lot", now.minus(9, ChronoUnit.DAYS), ItemStatus.RESOLVED, "What brand and colour is the wallet, and was anything inside?");
        Item i10 = item(bob, ItemType.FOUND, "AirPods Pro in white case",
                "Found on the auditorium floor after the morning lecture.",
                "Electronics", "Auditorium", now.minus(10, ChronoUnit.DAYS), ItemStatus.OPEN, "What is written or drawn on the inside of the case lid?");
        Item i11 = item(carol, ItemType.LOST, "Silver necklace with moon pendant",
                "Very sentimental, lost on the campus walk between the library and dorms.",
                "Jewelry", "Campus Walk", now.minus(11, ChronoUnit.DAYS), ItemStatus.OPEN, null);
        Item i12 = item(dave, ItemType.FOUND, "Calculator TI-84 Plus",
                "Found in exam room 204 after the math final.",
                "Electronics", "Science Building", now.minus(12, ChronoUnit.DAYS), ItemStatus.OPEN, "What initials or markings are on the back of the calculator?");
        Item i13 = item(carol, ItemType.LOST, "TI-84 Plus calculator",
                "Lost it after the math final — probably in or near exam room 204 in the Science Building.",
                "Electronics", "Science Building", now.minus(11, ChronoUnit.DAYS), ItemStatus.OPEN, null);
        Item i14 = item(bob, ItemType.FOUND, "14-inch laptop in a blue sleeve, covered in stickers",
                "Found under a study desk on the second floor of the library.",
                "Electronics", "Central Library", now.minus(1, ChronoUnit.DAYS), ItemStatus.OPEN, "Which stickers are on the lid and where are they placed?");

        // A completed legacy-style pickup: resolved without a verified badge.
        Claim completed = claim(i9, bob, "Hi! This might be mine — I lost my brown leather wallet in the parking lot last week. It was empty, I kept the cards separately. Could I get it back?");
        completed.setStatus(ClaimStatus.COMPLETED);
        completed.setDecidedAt(now.minus(8, ChronoUnit.DAYS));
        completed.setCompletedAt(now.minus(8, ChronoUnit.DAYS));
        completed.setHandoverVerified(false);
        completed.setOwnershipQuestionSnapshot(i9.getOwnershipQuestion());
        completed.setOwnershipAnswer("It's a brown Fossil bifold and it really was empty — the cards were still in my bag.");
        claimRepository.save(completed);

        // An approved reservation awaiting pickup.
        Claim approved = claim(i5, carol, "Hey! I lost my iPhone 15 on Monday in the lecture hall — clear case. Can you describe the lock screen wallpaper to confirm?");
        approved.setStatus(ClaimStatus.APPROVED);
        approved.setDecidedAt(now.minus(1, ChronoUnit.DAYS));
        approved.setOwnershipQuestionSnapshot(i5.getOwnershipQuestion());
        approved.setOwnershipAnswer("The wallpaper is a photo of my golden retriever puppy lying on a beach towel.");
        claimRepository.save(approved);

        Claim rejected = claim(i5, dave, "Is it black? I think that's mine.");
        rejected.setStatus(ClaimStatus.REJECTED);
        rejected.setDecidedAt(now.minus(2, ChronoUnit.DAYS));
        rejected.setOwnershipQuestionSnapshot(i5.getOwnershipQuestion());
        rejected.setOwnershipAnswer("Not sure, some photo I guess?");
        claimRepository.save(rejected);

        Claim keysClaim = claim(i4, alice, "Those could be my keys — my carabiner is red. When can I pick them up?");
        keysClaim.setOwnershipQuestionSnapshot(i4.getOwnershipQuestion());
        keysClaim.setOwnershipAnswer("Four keys on the ring plus a tiny torch and a gym fob on the carabiner.");
        claimRepository.save(keysClaim);

        Claim hoodieClaim = claim(i7, dave, "I think this might be my hoodie. Does it have a small tear near the left sleeve cuff?");
        hoodieClaim.setOwnershipQuestionSnapshot(i7.getOwnershipQuestion());
        hoodieClaim.setOwnershipAnswer("There's a small tear near the left sleeve cuff and wired earbuds in the front pocket.");
        claimRepository.save(hoodieClaim);

        log.info("Seeded {} users, {} items, {} claims",
                userRepository.count(), itemRepository.count(), claimRepository.count());
    }

    private User user(String name, String email, String password, String phone, Role role) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPhone(phone);
        user.setRole(role);
        return userRepository.save(user);
    }

    private Item item(User reporter, ItemType type, String title, String description,
                      String category, String location, Instant dateOccurred, ItemStatus status,
                      String ownershipQuestion) {
        Item item = new Item();
        item.setType(type);
        item.setTitle(title);
        item.setDescription(description);
        item.setCategory(category);
        item.setLocation(location);
        item.setDateOccurred(dateOccurred);
        item.setStatus(status);
        item.setReporter(reporter);
        item.setOwnershipQuestion(ownershipQuestion);
        if (status == ItemStatus.RESOLVED) {
            item.setResolvedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        }
        return itemRepository.save(item);
    }

    private Claim claim(Item item, User claimant, String message) {
        Claim claim = new Claim();
        claim.setItem(item);
        claim.setClaimant(claimant);
        claim.setMessage(message);
        return claimRepository.save(claim);
    }
}
