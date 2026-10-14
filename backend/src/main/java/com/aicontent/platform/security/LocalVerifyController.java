package com.aicontent.platform.security;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the screens show the local-verify banner. Outside that mode it only says the mode is off. */
@RestController
public class LocalVerifyController {

    public record Mode(boolean loginBypass, String dataLabel, String aiProvider) {}

    private final LocalVerifyGuard guard;
    private final AppProperties app;

    public LocalVerifyController(LocalVerifyGuard guard, AppProperties app) {
        this.guard = guard;
        this.app = app;
    }

    @GetMapping("/api/local-verify/mode")
    public ApiResponse<Mode> mode() {
        return ApiResponse.ok(guard.bypassActive()
                ? new Mode(true, guard.dataLabel(), app.ai().provider())
                : new Mode(false, null, null));
    }
}
