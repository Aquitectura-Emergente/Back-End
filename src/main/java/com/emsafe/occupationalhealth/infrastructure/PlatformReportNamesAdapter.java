package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.occupationalhealth.domain.services.ReportNamesPort;
import com.emsafe.plantconfiguration.domain.model.PlantZone;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Nombres de IAM y de Plant Configuration para los reportes (solo lectura). */
@Component
@RequiredArgsConstructor
public class PlatformReportNamesAdapter implements ReportNamesPort {

    private final UserRepository users;
    private final PlantZoneRepository zones;

    @Override
    public Optional<String> personName(Long userId) {
        return userId == null ? Optional.empty() : users.findById(userId).map(AppUser::getName);
    }

    @Override
    public Optional<String> zoneName(Long zoneId) {
        return zoneId == null ? Optional.empty() : zones.findById(zoneId).map(PlantZone::getName);
    }
}
