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
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<MatchRefreshService> self;
    private final ObjectProvider<MatchRetryQueue> retryQueue;
    private final int maxCatchUpItems;

    public MatchRefreshService(ItemRepository itemRepository,
                               MatchCandidateRepository candidateRepository,
                               MatchScoring scoring,
                               ApplicationEventPublisher events,
                               EntityManager entityManager,
                               ObjectProvider<MatchRefreshService> self,
                               ObjectProvider<MatchRetryQueue> retryQueue,
                               @Value("${app.matching.catch-up-max-items:500}") int maxCatchUpItems) {
        this.itemRepository = itemRepository;
        this.candidateRepository = candidateRepository;
        this.scoring = scoring;
        this.events = events;
        this.entityManager = entityManager;
        this.self = self;
        this.retryQueue = retryQueue;
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
     * Bounded restart catch-up. Recomputes and republishes snapshots for every
     * open report in batches of {@code app.matching.catch-up-max-items} source
     * reports per transaction, even when scores did not change, so a
     * later-installed alerts consumer can backfill. The bound limits how many
     * sources one batch replaces, never the candidate set a source is compared
     * against: each batch still scores against the complete set of open
     * reports, so published snapshots are always complete.
     */
    public RefreshSummary refreshAll() {
        List<Long> sourceIds = openItemIds();
        if (sourceIds.isEmpty()) {
            return new RefreshSummary(null, 0, 0, 0);
        }
        int affected = 0;
        int stored = 0;
        int published = 0;
        for (int start = 0; start < sourceIds.size(); start += maxCatchUpItems) {
            List<Long> batch = sourceIds.subList(
                    start, Math.min(start + maxCatchUpItems, sourceIds.size()));
            try {
                RefreshSummary summary = self.getObject().refreshBatch(batch);
                affected += summary.affectedItems();
                stored += summary.storedPairs();
                published += summary.publishedEvents();
            } catch (RuntimeException ex) {
                // A failed batch is queued report by report; the scheduled
                // retry owns its recovery instead of dropping it.
                log.warn("Match catch-up batch failed for reports {}; queued for retry",
                        batch, ex);
                batch.forEach(id -> retryQueue.getObject().enqueue(id));
            }
        }
        log.info("Match catch-up refreshed {} reports and stored {} pairs", affected, stored);
        return new RefreshSummary(null, affected, stored, published);
    }

    /**
     * One catch-up batch: locks the complete set of open reports, replaces the
     * persisted pairs touching the batch's sources, and publishes a complete
     * snapshot event for each source in the batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefreshSummary refreshBatch(Collection<Long> sourceIds) {
        Set<Long> batch = new LinkedHashSet<>(sourceIds);
        Map<Long, Item> openItems = lockOpenItems(null);
        if (openItems.isEmpty()) {
            return new RefreshSummary(null, 0, 0, 0);
        }
        Map<Long, MatchScoring.MatchProfile> profiles = profiles(openItems.values());
        Map<PairKey, ScoredPair> pairs = computePairs(openItems.values(), profiles);

        candidateRepository.deleteInvolving(batch);
        int stored = storePairsInvolving(pairs, batch);
        entityManager.flush();

        int published = publishEvents(batch, pairs, openItems);
        return new RefreshSummary(null, batch.size(), stored, published);
    }

    private List<Long> openItemIds() {
        return entityManager.createQuery("""
                        select i.id from Item i
                        where i.status = :status and i.deletedAt is null
                        order by i.id asc
                        """, Long.class)
                .setParameter("status", ItemStatus.OPEN)
                .getResultList();
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
            stored += storePair(entry, now);
        }
        return stored;
    }

    /** Stores every pair that has at least one endpoint in the batch. */
    private int storePairsInvolving(Map<PairKey, ScoredPair> pairs, Set<Long> batch) {
        int stored = 0;
        Instant now = Instant.now();
        for (Map.Entry<PairKey, ScoredPair> entry : pairs.entrySet()) {
            if (!batch.contains(entry.getKey().lostItemId())
                    && !batch.contains(entry.getKey().foundItemId())) {
                continue;
            }
            stored += storePair(entry, now);
        }
        return stored;
    }

    private int storePair(Map.Entry<PairKey, ScoredPair> entry, Instant now) {
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
        return 1;
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
