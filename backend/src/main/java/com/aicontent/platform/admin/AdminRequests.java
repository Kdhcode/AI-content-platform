package com.aicontent.platform.admin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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

    /** Manual content: {@code body} is optional (empty draft); size and structure are checked by ContentBodyValidator. */
    public record ContentCreateRequest(
            @NotNull String contentType,
            String title,
            JsonNode body) {}

    /** Omitted title / body keep their value; {@code version} is the one the editor loaded. */
    public record ContentPatchRequest(
            @NotNull @PositiveOrZero Long version,
            String title,
            JsonNode body) {}

    /** Body of every content status action; {@code version} is the one the editor has (409 otherwise). */
    public record ContentActionRequest(
            @NotNull @PositiveOrZero Long version,
            @Size(max = 2000) String reason) {}

    /** A4: exactly the reference field of {@code kind}; role defaults to SUPPORTING. */
    public record ContentSourceAddRequest(
            @NotNull @PositiveOrZero Long version,
            @NotNull String kind,
            @Positive Long issueId,
            @Positive Long newsArticleId,
            @Size(max = 4000) String externalUrl,
            @Size(max = 1000) String label,
            String role) {}

    /** A4: role and/or label; null keeps the value, an empty label clears it (not for MANUAL). */
    public record ContentSourcePatchRequest(
            @NotNull @PositiveOrZero Long version,
            String role,
            @Size(max = 1000) String label) {}

    /** A5: siteVisible defaults to true, blogVisible to false, displayPosition to BODY_BOTTOM. */
    public record ContentLinkAddRequest(
            @NotNull @PositiveOrZero Long version,
            @NotNull String linkType,
            @Size(max = 1000) String title,
            @Size(max = 4000) String url,
            Boolean siteVisible,
            Boolean blogVisible,
            String displayPosition) {}

    /** A5: null keeps the value. */
    public record ContentLinkPatchRequest(
            @NotNull @PositiveOrZero Long version,
            String linkType,
            @Size(max = 1000) String title,
            @Size(max = 4000) String url,
            Boolean siteVisible,
            Boolean blogVisible,
            String displayPosition) {}

    /** A5: every link id of the content exactly once, in the new order (at most 500, the per-content link limit). */
    public record ContentLinkOrderRequest(
            @NotNull @PositiveOrZero Long version,
            @NotNull @Size(max = com.aicontent.platform.content.ContentRelationService.MAX_LINKS_PER_CONTENT) List<Long> linkIds) {}

    public record IssuePatchRequest(
            @Size(max = 300) String title,
            @Size(max = 5000) String summary,
            @Size(max = 50) String category,
            @Size(max = 20) String status,
            @Size(max = 500) String reason) {}
}
