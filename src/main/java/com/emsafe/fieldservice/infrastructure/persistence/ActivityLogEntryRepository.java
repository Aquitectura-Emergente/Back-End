package com.emsafe.fieldservice.infrastructure.persistence;

import com.emsafe.fieldservice.domain.model.ActivityLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityLogEntryRepository extends JpaRepository<ActivityLogEntry, Long> {

    List<ActivityLogEntry> findByWorkOrderIdOrderByIdDesc(Long workOrderId);
}
