package com.emsafe.alerts.application;

import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import com.emsafe.alerts.interfaces.rest.dto.CreateAlarmRequest;
import com.emsafe.alerts.domain.model.Alert;
import com.emsafe.alerts.infrastructure.persistence.AlertRepository;
import com.emsafe.shared.exception.ResourceNotFoundException;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AlarmService {

    private final AlertRepository alertRepository;
    private final UserRepository userRepository;

    public List<AlertDto> getAll() {
        return alertRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(AlertDto::from).toList();
    }

    @Transactional
    public AlertDto create(CreateAlarmRequest req) {
        boolean specific = Alert.TO_SPECIFIC.equalsIgnoreCase(req.recipientType());

        Alert alert = Alert.builder()
                .type(req.type())
                .icon(req.icon())
                .title(req.title())
                .description(req.description())
                .relativeTime(StringUtils.hasText(req.relativeTime()) ? req.relativeTime() : "Just now")
                .recipientType(specific ? Alert.TO_SPECIFIC : Alert.TO_ALL)
                .sensor(req.sensor())
                .build();

        if (specific && req.clientIds() != null && !req.clientIds().isEmpty()) {
            List<AppUser> clients = userRepository.findAllById(req.clientIds());
            alert.addressTo(
                    clients.stream().map(AppUser::getId).toList(),
                    clients.stream().map(AppUser::getName).collect(Collectors.joining(", ")));
        } else {
            alert.addressToEveryone();
        }

        return AlertDto.from(alertRepository.save(alert));
    }

    @Transactional
    public AlertDto resolve(Long id) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Alarm", id));
        alert.resolve();
        return AlertDto.from(alertRepository.save(alert));
    }

    @Transactional
    public void delete(Long id) {
        if (!alertRepository.existsById(id)) {
            throw new ResourceNotFoundException("Alarm", id);
        }
        alertRepository.deleteById(id);
    }
}
