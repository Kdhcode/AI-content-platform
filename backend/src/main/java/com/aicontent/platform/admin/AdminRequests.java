package com.aicontent.platform.admin;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies of the admin API (validated with Bean Validation; violations -> 400 VALIDATION_ERROR). */
public final class AdminRequests {

    private AdminRequests() {}

    public record MergeRequest(
            @NotEmpty @Size(max = 50) List<@NotNull @Positive Long> sourceIssueIds,
            @Positive Long targetIssueId,
            @Size(max = 500) String reason) {}

    public record SplitRequest(
            @NotEmpty @Size(max = 200) List<@NotNull @Positive Long> articleIds,
            @Size(max = 300) String newTitle,
            @Size(max = 500) String reason) {}

    public record MoveRequest(
            @NotNull @Positive Long targetIssueId,
            @Size(max = 500) String reason) {}

    public record IssuePatchRequest(
            @Size(max = 300) String title,
            @Size(max = 5000) String summary,
            @Size(max = 50) String category,
            @Size(max = 20) String status,
            @Size(max = 500) String reason) {}
}
