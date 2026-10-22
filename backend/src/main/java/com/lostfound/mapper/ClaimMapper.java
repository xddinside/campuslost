package com.lostfound.mapper;

import com.lostfound.dto.ClaimResponse;
import com.lostfound.model.Claim;
import org.springframework.stereotype.Component;

/** Authorized claim serialization; private evidence and contacts stay out. */
@Component
public class ClaimMapper {

    public ClaimResponse toResponse(Claim claim) {
        return new ClaimResponse(
                claim.getId(),
                claim.getItem().getId(),
                claim.getItem().getType(),
                claim.getItem().getTitle(),
                claim.getItem().getReporter().getId(),
                claim.getClaimant().getId(),
                claim.getClaimant().getName(),
                claim.getMessage(),
                claim.getStatus(),
                claim.getLostReport() == null ? null : claim.getLostReport().getId(),
                claim.getVersion(),
                claim.getCreatedAt(),
                claim.getDecidedAt(),
                claim.getCompletedAt()
        );
    }
}
