package com.emsafe.devicemanagement.domain.repositories;

import com.emsafe.devicemanagement.domain.model.PlugCommand;

import java.util.Optional;

public interface PlugCommandRepository {
    PlugCommand save(PlugCommand command);

    Optional<PlugCommand> findByCommandId(String commandId);

    Optional<PlugCommand> findLatestForPlug(String plugSerial);
}
