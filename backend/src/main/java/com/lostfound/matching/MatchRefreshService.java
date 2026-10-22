package com.lostfound.matching;

import com.lostfound.contract.MatchCandidate;
import com.lostfound.contract.MatchCandidatesChangedEvent;
import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import com.lostfound.repository.ItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Recomputes match snapshots and publishes complete replacement events.
 *
 * <p>Every refresh takes pessimistic write locks on the OPEN reports it reads,
 * in ascending ID order, then recomputes pairs from those committed rows. The
 * snapshot and the event therefore always carry the same committed versions;
 * a concurrent edit either finishes before the refresh reads, or waits and
 * triggers its own after-commit refresh. Old candidates are deleted and
 * replaced rather than patched, so a report can never keep a stale pair.</p>
 */
@Service
public class MatchRefreshService {

    private static final Logger log = LoggerFactory.getLogger(MatchRefreshService.class);

    private final ItemRepository itemRepository;
    private final MatchCandidateRepository candidateRepository;
    private final MatchScoring scoring;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final int maxCatchUpItems;

    public MatchRefreshService(ItemRepository itemRepository,
                               MatchCandidateRepository candidateRepository,
                               MatchScoring scoring,
                               ApplicationEventPublisher events,
                               EntityManager entityManager,
                               @Value("${app.matching.catch-up-max-items:500}") int maxCatchUpItems) {
        this.itemRepository = itemRepository;
        this.candidateRepository = candidateRepository;
        this.scoring = scoring;
        this.events = events;
        this.entityManager = entityManager;
        this.maxCatchUpItems = Math.max(1, maxCatchUpItems);
    }

    public record RefreshSummary(Long itemId, int affectedItems, int storedPairs, int publishedEvents) {
    }

    /**
     * Refresh the changed report plus every previously or newly related report,
     * replacing whole snapshots for the connected group.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefreshSummary refreshForItem(Long itemId) {
        Item source = itemRepository.findById(itemId).orElse(null);
        if (source == null) {
            return new RefreshSummary(itemId, 0, 0, 0);
        }

        Set<Long> seeds = new LinkedHashSet<>();
        seeds.add(itemId);
        seeds.addAll(candidateRepository.findRelatedItemIds(itemId));

        Map<Long, Item> openItems = lockOpenItems(null);
        Map<Long, MatchScoring.MatchProfile> profiles = profiles(openItems.values());
        Map<PairKey, ScoredPair> pairs = computePairs(openItems.values(), profiles);

        Set<Long> affected = connectedComponent(seeds, pairs);
        // Include source even when it is no longer open so its old rows are purged
        // and an empty replacement snapshot is published.
        affected.add(itemId);

        candidateRepository.deleteInvolving(affected);
        int stored = storePairs(pairs, affected);
        entityManager.flush();

        int published = publishEvents(affected, pairs, openItems);
        return new RefreshSummary(itemId, affected.size(), stored, published);
    }

    /**
     * Bounded restart catch-up. Recomputes and republishes snapshots for up to
     * {@code app.matching.catch-up-max-items} open reports, even when scores did
     * not change, so a later-installed alerts consumer can backfill.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefreshSummary refreshAll() {
        Map<Long, Item> openItems = lockOpenItems(maxCatchUpItems);
        if (openItems.isEmpty()) {
            return new RefreshSummary(null, 0, 0, 0);
        }
        Map<Long, MatchScoring.MatchProfile> profiles = profiles(openItems.values());
        Map<PairKey, ScoredPair> pairs = computePairs(openItems.values(), profiles);
        Set<Long> affected = new LinkedHashSet<>(openItems.keySet());

        candidateRepository.deleteBetween(affected);
        int stored = storePairs(pairs, affected);
        entityManager.flush();

        int published = publishEvents(affected, pairs, openItems);
        log.info("Match catch-up refreshed {} reports and stored {} pairs", affected.size(), stored);
        return new RefreshSummary(null, affected.size(), stored, published);
    }

    private Map<Long, Item> lockOpenItems(Integer limit) {
        var query = entityManager.createQuery("""
                        select i from Item i
                        where i.status = :status and i.deletedAt is null
                        order by i.id asc
                        """, Item.class)
                .setParameter("status", ItemStatus.OPEN)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE);
        if (limit != null) {
            query.setMaxResults(limit);
        }
        Map<Long, Item> byId = new LinkedHashMap<>();
        for (Item item : query.getResultList()) {
            byId.put(item.getId(), item);
        }
        return byId;
    }

    private Map<Long, MatchScoring.MatchProfile> profiles(Collection<Item> items) {
        Map<Long, MatchScoring.MatchProfile> profiles = new HashMap<>();
        for (Item item : items) {
            profiles.put(item.getId(), scoring.profile(item));
        }
        return profiles;
    }

    private Map<PairKey, ScoredPair> computePairs(Collection<Item> items,
                                                   Map<Long, MatchScoring.MatchProfile> profiles) {
        List<Item> lostReports = items.stream()
                .filter(item -> item.getType() == ItemType.LOST)
                .sorted(Comparator.comparing(Item::getId))
                .toList();
        List<Item> foundReports = items.stream()
                .filter(item -> item.getType() == ItemType.FOUND)
                .sorted(Comparator.comparing(Item::getId))
                .toList();

        Map<PairKey, ScoredPair> pairs = new HashMap<>();
        for (Item lost : lostReports) {
            for (Item found : foundReports) {
                Optional<MatchScoring.MatchScore> score = scoring.score(
                        profiles.get(lost.getId()), profiles.get(found.getId()));
                score.ifPresent(match -> pairs.put(new PairKey(lost.getId(), found.getId()),
                        new ScoredPair(lost, found, match)));
            }
        }
        return pairs;
    }

    /**
     * Walk the candidate graph from the touched reports. Newly related reports
     * join the group, so both authors' complete lists are replaced consistently.
     */
    private Set<Long> connectedComponent(Set<Long> seeds, Map<PairKey, ScoredPair> pairs) {
        Map<Long, Set<Long>> adjacency = new HashMap<>();
        for (PairKey key : pairs.keySet()) {
            adjacency.computeIfAbsent(key.lostItemId(), ignored -> new LinkedHashSet<>())
                    .add(key.foundItemId());
            adjacency.computeIfAbsent(key.foundItemId(), ignored -> new LinkedHashSet<>())
                    .add(key.lostItemId());
        }
        Set<Long> visited = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        for (Long seed : seeds) {
            if (visited.add(seed)) {
                queue.add(seed);
            }
        }
        while (!queue.isEmpty()) {
            Long next = queue.removeFirst();
            for (Long neighbour : adjacency.getOrDefault(next, Set.of())) {
                if (visited.add(neighbour)) {
                    queue.add(neighbour);
                }
            }
        }
        return visited;
    }

    private int storePairs(Map<PairKey, ScoredPair> pairs, Set<Long> affected) {
        int stored = 0;
        Instant now = Instant.now();
        for (Map.Entry<PairKey, ScoredPair> entry : pairs.entrySet()) {
            if (!affected.contains(entry.getKey().lostItemId())
                    || !affected.contains(entry.getKey().foundItemId())) {
                continue;
            }
            ScoredPair pair = entry.getValue();
            MatchCandidateEntity entity = new MatchCandidateEntity();
            entity.setLostItem(pair.lost());
            entity.setFoundItem(pair.found());
            entity.setLostItemVersion(pair.lost().getVersion());
            entity.setFoundItemVersion(pair.found().getVersion());
            entity.setScore(pair.score().score());
            entity.setReasons(String.join("\n", pair.score().reasons()));
            entity.setUpdatedAt(now);
            candidateRepository.save(entity);
            stored++;
        }
        return stored;
    }

    private int publishEvents(Set<Long> affected,
                              Map<PairKey, ScoredPair> pairs,
                              Map<Long, Item> openItems) {
        int published = 0;
        for (Long itemId : affected.stream().sorted().toList()) {
            Item current = currentItem(itemId, openItems);
            if (current == null) {
                continue;
            }
            List<MatchCandidate> candidates = pairs.values().stream()
                    .filter(pair -> pair.involves(itemId))
                    .sorted(Comparator
                            .comparingInt((ScoredPair pair) -> pair.score().score()).reversed()
                            .thenComparingLong(pair -> pair.otherItemId(itemId)))
                    .map(pair -> new MatchCandidate(
                            pair.lost().getId(),
                            pair.found().getId(),
                            pair.lost().getReporter().getId(),
                            pair.found().getReporter().getId(),
                            pair.score().score(),
                            pair.score().reasons(),
                            pair.lost().getVersion(),
                            pair.found().getVersion()))
                    .toList();
            events.publishEvent(new MatchCandidatesChangedEvent(
                    current.getId(), current.getVersion(), candidates));
            published++;
        }
        return published;
    }

    private Item currentItem(Long itemId, Map<Long, Item> openItems) {
        Item locked = openItems.get(itemId);
        if (locked != null) {
            return locked;
        }
        Item item = entityManager.find(Item.class, itemId);
        if (item != null) {
            // Not part of the locked set (closed/deleted): read the newest version
            // before the empty snapshot is published.
            entityManager.refresh(item);
        }
        return item;
    }

    private record PairKey(Long lostItemId, Long foundItemId) {
    }

    private record ScoredPair(Item lost, Item found, MatchScoring.MatchScore score) {

        boolean involves(Long itemId) {
            return lost.getId().equals(itemId) || found.getId().equals(itemId);
        }

        long otherItemId(Long itemId) {
            return lost.getId().equals(itemId) ? found.getId() : lost.getId();
        }
    }
}
