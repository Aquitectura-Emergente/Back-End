package com.emsafe.fieldservice.interfaces.rest;

import com.emsafe.iam.infrastructure.security.JwtUtil;
import com.emsafe.fieldservice.interfaces.rest.dto.HistoryDto;
import com.emsafe.fieldservice.application.HistoryService;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;
    private final JwtUtil jwtUtil;

    /** Admin history — no technician filter */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<HistoryDto>>> getHistory(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(ApiResponse.ok(historyService.findAll(null, status, search)));
    }

    /** Tech history — filtered by the logged-in technician */
    @GetMapping("/tech/history")
    public ResponseEntity<ApiResponse<List<HistoryDto>>> getTechHistory(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        Long technicianId = extractUserId(request);
        return ResponseEntity.ok(ApiResponse.ok(historyService.findAll(technicianId, status, search)));
    }

    @GetMapping("/history/paged")
    public ResponseEntity<ApiResponse<com.emsafe.shared.dto.PageResponse<HistoryDto>>> getHistoryPaged(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.ok(historyService.findAllPaged(null, status, search, page, size)));
    }

    @GetMapping("/tech/history/paged")
    public ResponseEntity<ApiResponse<com.emsafe.shared.dto.PageResponse<HistoryDto>>> getTechHistoryPaged(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long technicianId = extractUserId(request);
        return ResponseEntity.ok(ApiResponse.ok(historyService.findAllPaged(technicianId, status, search, page, size)));
    }

    @GetMapping("/history/export/csv")
    public void exportCsv(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=\"history.csv\"");
        List<HistoryDto> records = historyService.findAll(null, status, search);
        PrintWriter writer = response.getWriter();
        writer.println("Order ID,Completion Date,Completion Time,Client,Site,Service Type,Technician,Status");
        for (HistoryDto h : records) {
            writer.printf("%s,%s,%s,\"%s\",\"%s\",%s,%s,%s%n",
                    h.orderId(), h.completionDate(), h.completionTime(),
                    h.client(), h.site(), h.serviceType(), h.technician(), h.status());
        }
        writer.flush();
    }

    private Long extractUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return jwtUtil.extractUserId(header.substring(7));
        }
        return null;
    }
}
