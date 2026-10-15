package com.aicontent.platform.admin;

import com.aicontent.platform.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the current request is authenticated as, so the admin UI can show only the actions the account may take
 * (the server still checks every action). Requires an admin role like every /api/admin path.
 */
@RestController
public class MeAdminController {

    public record Me(String username, List<String> roles) {}

    @GetMapping("/api/admin/me")
    public ApiResponse<Me> me(Authentication auth) {
        List<String> roles = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .toList();
        return ApiResponse.ok(new Me(auth.getName(), roles));
    }
}
