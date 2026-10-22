package com.lostfound.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MatchCandidateRepository extends JpaRepository<MatchCandidateEntity, Long> {

    @Query("""
            select c from MatchCandidateEntity c
            join fetch c.lostItem l
            join fetch c.foundItem f
            where l.id = :itemId or f.id = :itemId
            order by c.score desc, c.id asc
            """)
    List<MatchCandidateEntity> findAllInvolving(@Param("itemId") Long itemId);

    @Query("""
            select c from MatchCandidateEntity c
            join fetch c.lostItem l
            join fetch c.foundItem f
            order by c.score desc, c.id asc
            """)
    List<MatchCandidateEntity> findAllWithItems();

    Optional<MatchCandidateEntity> findByLostItem_IdAndFoundItem_Id(Long lostItemId, Long foundItemId);

    @Query("""
            select case when c.lostItem.id = :itemId then c.foundItem.id else c.lostItem.id end
            from MatchCandidateEntity c
            where c.lostItem.id = :itemId or c.foundItem.id = :itemId
            """)
    List<Long> findRelatedItemIds(@Param("itemId") Long itemId);

    @Modifying
    @Query("delete from MatchCandidateEntity c where c.lostItem.id in :ids or c.foundItem.id in :ids")
    int deleteInvolving(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query("delete from MatchCandidateEntity c where c.lostItem.id in :ids and c.foundItem.id in :ids")
    int deleteBetween(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query("delete from MatchCandidateEntity c where c.lostItem.id = :itemId or c.foundItem.id = :itemId")
    int deleteInvolving(@Param("itemId") Long itemId);
}
