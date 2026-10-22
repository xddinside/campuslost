package com.lostfound.repository;

import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "update items set photo_id = null where photo_id is not null", nativeQuery = true)
    void clearPhotoReferences();

    @Query("""
            select i from Item i
            where i.deletedAt is null
              and (:status is null or i.status = :status)
              and (:type is null or i.type = :type)
              and (:category is null or i.category = :category)
              and (:location is null or lower(i.location) like lower(concat('%', :location, '%')))
              and (:q is null or lower(i.title) like lower(concat('%', :q, '%'))
                     or lower(i.description) like lower(concat('%', :q, '%'))
                     or lower(i.location) like lower(concat('%', :q, '%')))
            order by i.createdAt desc
            """)
    List<Item> search(@Param("type") ItemType type,
                      @Param("status") ItemStatus status,
                      @Param("category") String category,
                      @Param("location") String location,
                      @Param("q") String q);

    @Query("select i from Item i where i.deletedAt is null order by i.createdAt desc")
    List<Item> findAllVisibleOrderByCreatedAtDesc();

    @Query("select i from Item i where i.deletedAt is null and i.reporter.id = :reporterId order by i.createdAt desc")
    List<Item> findVisibleByReporterIdOrderByCreatedAtDesc(@Param("reporterId") Long reporterId);

    Optional<Item> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id = :id")
    Optional<Item> findByIdForUpdate(@Param("id") Long id);
}
