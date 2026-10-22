package com.emsafe.fieldservice.infrastructure.persistence;

import com.emsafe.fieldservice.domain.model.WorkOrder;
import com.emsafe.fieldservice.domain.model.WorkOrderStatus;
import com.emsafe.fieldservice.domain.model.WorkOrderType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    List<WorkOrder> findByStatus(WorkOrderStatus status);

    List<WorkOrder> findByType(WorkOrderType type);

    List<WorkOrder> findByStatusAndType(WorkOrderStatus status, WorkOrderType type);

    List<WorkOrder> findByTechnicianId(Long technicianId);

    List<WorkOrder> findByTechnicianIdAndStatus(Long technicianId, WorkOrderStatus status);

    @Query("""
            SELECT wo FROM WorkOrder wo
            WHERE wo.status IN :statuses
              AND (:type IS NULL OR wo.type = :type)
              AND (:search IS NULL
                   OR LOWER(wo.client) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(wo.orderId) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(wo.location) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    List<WorkOrder> search(
            @Param("statuses") List<WorkOrderStatus> statuses,
            @Param("type") WorkOrderType type,
            @Param("search") String search,
            Sort sort
    );

    @Query("""
            SELECT wo FROM WorkOrder wo
            WHERE wo.status IN :statuses
              AND (:type IS NULL OR wo.type = :type)
              AND (:search IS NULL
                   OR LOWER(wo.client) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(wo.orderId) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(wo.location) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<WorkOrder> searchPaged(
            @Param("statuses") List<WorkOrderStatus> statuses,
            @Param("type") WorkOrderType type,
            @Param("search") String search,
            Pageable pageable
    );

    List<WorkOrder> findTop4ByOrderByScheduledDateDesc();

    long countByStatus(WorkOrderStatus status);

    @Query("SELECT COUNT(wo) FROM WorkOrder wo WHERE wo.city LIKE %:suffix%")
    long countByCitySuffix(@Param("suffix") String suffix);
}
