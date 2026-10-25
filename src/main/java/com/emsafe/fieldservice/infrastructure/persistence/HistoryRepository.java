package com.emsafe.fieldservice.infrastructure.persistence;

import com.emsafe.fieldservice.domain.model.History;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HistoryRepository extends JpaRepository<History, Long> {

    List<History> findByTechnicianId(Long technicianId);

    @Query("""
            SELECT h FROM History h
            WHERE (:technicianId IS NULL OR h.technicianId = :technicianId)
              AND (:status IS NULL OR h.status = :status)
              AND (:search IS NULL
                   OR LOWER(h.client) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(h.orderId) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY h.completionDate DESC
            """)
    List<History> search(
            @Param("technicianId") Long technicianId,
            @Param("status") String status,
            @Param("search") String search
    );

    @Query("""
            SELECT h FROM History h
            WHERE (:technicianId IS NULL OR h.technicianId = :technicianId)
              AND (:status IS NULL OR h.status = :status)
              AND (:search IS NULL
                   OR LOWER(h.client) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(h.orderId) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY h.completionDate DESC
            """)
    Page<History> searchPaged(
            @Param("technicianId") Long technicianId,
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable
    );
}
