package com.lostfound.repository;

import com.lostfound.model.Claim;
import com.lostfound.model.ClaimStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id")
    Optional<Claim> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select new com.lostfound.repository.ClaimRef(c.item.id, c.lostReport.id)
            from Claim c
            where c.id = :id
            """)
    Optional<ClaimRef> findRefById(@Param("id") Long id);

    List<Claim> findByItemIdOrderByCreatedAtDesc(Long itemId);

    List<Claim> findByItemIdAndStatusOrderByCreatedAtDesc(Long itemId, ClaimStatus status);

    List<Claim> findByItemIdAndStatusInOrderByCreatedAtDesc(Long itemId, Collection<ClaimStatus> statuses);

    List<Claim> findByLostReportIdAndStatusIn(Long lostReportId, Collection<ClaimStatus> statuses);

    List<Claim> findByClaimantIdOrderByCreatedAtDesc(Long claimantId);

    long countByItemIdAndStatus(Long itemId, ClaimStatus status);

    long countByItemIdAndStatusIn(Long itemId, Collection<ClaimStatus> statuses);

    boolean existsByItemIdAndClaimantIdAndStatusIn(Long itemId, Long claimantId, Collection<ClaimStatus> statuses);

    @Query("""
            select count(c) from Claim c
            where (c.item.id = :itemId or c.lostReport.id = :itemId)
              and c.handoverVerified = true
            """)
    long countVerifiedHandoverForItem(@Param("itemId") Long itemId);
}
