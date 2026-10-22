package com.lostfound.repository;

import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ItemRepository extends JpaRepository<Item, Long> {

    @Query("""
            select i from Item i
            where i.status <> com.lostfound.model.ItemStatus.CLOSED
              and (:type is null or i.type = :type)
              and (:category is null or i.category = :category)
              and (:location is null or lower(i.location) like lower(concat('%', :location, '%')))
              and (:q is null or lower(i.title) like lower(concat('%', :q, '%'))
                     or lower(i.description) like lower(concat('%', :q, '%')))
            order by i.createdAt desc
            """)
    List<Item> search(@Param("type") ItemType type,
                      @Param("category") String category,
                      @Param("location") String location,
                      @Param("q") String q);

    List<Item> findByReporterIdOrderByCreatedAtDesc(Long reporterId);
}
