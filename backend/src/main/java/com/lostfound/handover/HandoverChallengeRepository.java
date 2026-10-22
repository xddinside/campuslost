package com.lostfound.handover;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface HandoverChallengeRepository extends JpaRepository<HandoverChallenge, Long> {

    Optional<HandoverChallenge> findByClaimId(Long claimId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HandoverChallenge h where h.claim.id = :claimId")
    Optional<HandoverChallenge> findByClaimIdForUpdate(@Param("claimId") Long claimId);
}
