package com.aicontent.platform.job;

/** What a handler gets: the claimed job and the id of the worker that owns it. */
public record JobContext(AsyncJob job, String workerId) {

    public long requireLong(String field) {
        var node = job.payload() == null ? null : job.payload().get(field);
        if (node == null || !node.canConvertToLong() || node.isNull()) {
            throw new JobExecutionException("INVALID_PAYLOAD",
                    "job " + job.id() + " payload is missing numeric field '" + field + "'", false);
        }
        return node.asLong();
    }

    public boolean optionalBoolean(String field, boolean defaultValue) {
        var node = job.payload() == null ? null : job.payload().get(field);
        return node == null || node.isNull() ? defaultValue : node.asBoolean(defaultValue);
    }

    public String correlationId() {
        return job.correlationId();
    }
}
