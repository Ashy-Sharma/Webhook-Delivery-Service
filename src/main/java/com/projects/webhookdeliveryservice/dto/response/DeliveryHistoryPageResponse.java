package com.projects.webhookdeliveryservice.dto.response;

import java.util.List;

public record DeliveryHistoryPageResponse(
        List<DeliveryHistoryResponse> content,
        long totalElements,
        int page,
        int size,
        int totalPages) {
}
