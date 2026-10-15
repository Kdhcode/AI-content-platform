package com.aicontent.platform.common;

import org.springframework.http.HttpStatus;

/** Stable, machine readable error codes returned in {@code error.code}. Never rename a published code. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    ARTICLE_NOT_FOUND(HttpStatus.NOT_FOUND, "기사를 찾을 수 없습니다."),
    ISSUE_NOT_FOUND(HttpStatus.NOT_FOUND, "이슈를 찾을 수 없습니다."),
    SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "뉴스 소스를 찾을 수 없습니다."),
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다."),
    CONTENT_NOT_FOUND(HttpStatus.NOT_FOUND, "콘텐츠를 찾을 수 없습니다."),
    CONTENT_SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "이 콘텐츠의 출처를 찾을 수 없습니다."),
    CONTENT_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "이 콘텐츠의 관련 URL을 찾을 수 없습니다."),

    ARTICLE_NOT_ANALYZABLE(HttpStatus.CONFLICT, "재분석할 수 없는 기사입니다."),
    ARTICLE_NOT_IN_ISSUE(HttpStatus.CONFLICT, "해당 이슈에 연결된 기사가 아닙니다."),

    ISSUE_STATE_INVALID(HttpStatus.CONFLICT, "현재 이슈 상태에서는 수행할 수 없는 작업입니다."),
    ISSUE_MERGE_SELF(HttpStatus.BAD_REQUEST, "이슈를 자기 자신에 병합할 수 없습니다."),
    ISSUE_MERGE_SOURCE_INVALID(HttpStatus.CONFLICT, "병합할 수 없는 이슈가 포함되어 있습니다."),
    ISSUE_SPLIT_WOULD_EMPTY(HttpStatus.CONFLICT, "분리 후 원본 이슈에 기사가 남아야 합니다."),

    CONTENT_BODY_INVALID(HttpStatus.BAD_REQUEST, "콘텐츠 본문 형식이 올바르지 않습니다."),
    CONTENT_VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 곳에서 먼저 저장된 콘텐츠입니다. 최신 내용을 다시 불러오세요."),
    CONTENT_STATE_INVALID(HttpStatus.CONFLICT, "현재 콘텐츠 상태에서는 수행할 수 없는 작업입니다."),
    CONTENT_NOT_READY(HttpStatus.UNPROCESSABLE_ENTITY, "검수 요청·승인에 필요한 내용이 갖춰지지 않았습니다."),
    CONTENT_SOURCE_DUPLICATE(HttpStatus.CONFLICT, "이미 이 콘텐츠의 출처로 등록돼 있습니다."),
    CONTENT_LINK_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "콘텐츠당 관련 URL은 500개까지 등록할 수 있습니다."),
    CONTENT_SOURCE_PROTECTED(HttpStatus.CONFLICT, "원천 이슈 출처는 삭제할 수 없습니다."),
    CONTENT_SOURCE_INVALID(HttpStatus.CONFLICT, "출처로 쓸 수 없는 대상입니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 Idempotency-Key로 다른 요청이 이미 처리되었습니다."),

    JOB_NOT_RETRYABLE(HttpStatus.CONFLICT, "재시도할 수 없는 작업 상태입니다."),
    JOB_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 작업 상태입니다."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
