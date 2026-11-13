package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.PlugCommand;
import com.emsafe.devicemanagement.domain.repositories.PlugCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaPlugCommandRepository implements PlugCommandRepository {
    private final PlugCommandJpaRepository commands;

    @Override
    public PlugCommand save(PlugCommand command) {
        return commands.save(command);
    }

    @Override
    public Optional<PlugCommand> findByCommandId(String commandId) {
        return commands.findByCommandId(commandId);
    }

    @Override
    public Optional<PlugCommand> findLatestForPlug(String plugSerial) {
        return commands.findFirstByPlugSerialOrderByRequestedAtDescIdDesc(plugSerial);
    }
}
