package com.emsafe.occupationalhealth.infrastructure.persistence;

import com.emsafe.occupationalhealth.domain.model.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, Long> {

    boolean existsByConsumerNameAndOrganizationIdAndEventId(String consumerName,
                                                            Long organizationId,
                                                            String eventId);

    /** Para RetentionJob: el inbox se purga a los 30 días de procesado. */
    List<ProcessedMessage> findByProcessedAtBefore(LocalDateTime threshold);
}
