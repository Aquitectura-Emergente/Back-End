package com.emsafe.fieldservice.interfaces.rest.dto;

import com.emsafe.fieldservice.domain.model.ActivityLogEntry;

public record ActivityLogDto(
        Long id,
        String event,
        String time
) {
    public static ActivityLogDto from(ActivityLogEntry e) {
        return new ActivityLogDto(e.getId(), e.getEvent(), e.getLogTime());
    }
}
