package com.lostfound.repository;

import com.lostfound.model.Claim;
import com.lostfound.model.ClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findByItemIdOrderByCreatedAtDesc(Long itemId);

    List<Claim> findByItemIdAndStatusOrderByCreatedAtDesc(Long itemId, ClaimStatus status);

    List<Claim> findByClaimantIdOrderByCreatedAtDesc(Long claimantId);

    long countByItemIdAndStatus(Long itemId, ClaimStatus status);

    boolean existsByItemIdAndClaimantIdAndStatus(Long itemId, Long claimantId, ClaimStatus status);
}
