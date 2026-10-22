package com.emsafe.fieldservice.application;

import com.emsafe.fieldservice.interfaces.rest.dto.HistoryDto;
import com.emsafe.fieldservice.infrastructure.persistence.HistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final HistoryRepository historyRepository;

    /**
     * @param technicianId filter by technician (null = all, for admin portal)
     * @param status       filter by status (null = all)
     * @param search       partial search on client or orderId
     */
    public com.emsafe.shared.dto.PageResponse<HistoryDto> findAllPaged(Long technicianId, String status, String search, int page, int size) {
        String statusFilter = StringUtils.hasText(status) ? status.toLowerCase() : null;
        String searchFilter = StringUtils.hasText(search) ? search : null;
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return com.emsafe.shared.dto.PageResponse.of(
                historyRepository.searchPaged(technicianId, statusFilter, searchFilter, pageable)
                        .map(HistoryDto::from)
        );
    }

    public List<HistoryDto> findAll(Long technicianId, String status, String search) {
        String statusFilter = StringUtils.hasText(status) ? status.toLowerCase() : null;
        String searchFilter = StringUtils.hasText(search) ? search : null;

        return historyRepository.search(technicianId, statusFilter, searchFilter)
                .stream().map(HistoryDto::from).toList();
    }
}
