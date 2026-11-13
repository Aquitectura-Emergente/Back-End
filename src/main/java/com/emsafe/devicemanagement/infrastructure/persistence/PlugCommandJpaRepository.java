package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.PlugCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlugCommandJpaRepository extends JpaRepository<PlugCommand, Long> {
    Optional<PlugCommand> findByCommandId(String commandId);

    Optional<PlugCommand> findFirstByPlugSerialOrderByRequestedAtDescIdDesc(String plugSerial);
}
