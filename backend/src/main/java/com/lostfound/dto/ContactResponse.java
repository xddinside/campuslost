package com.lostfound.dto;

public record ContactResponse(Long claimId, Participant reporter, Participant claimant) {

    public record Participant(String name, String email, String phone) {
    }
}
