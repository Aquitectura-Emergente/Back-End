package com.emsafe.alerts.infrastructure.persistence;

import com.emsafe.alerts.domain.model.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findAllByOrderByCreatedAtDesc();

    long countByType(String type);
}
