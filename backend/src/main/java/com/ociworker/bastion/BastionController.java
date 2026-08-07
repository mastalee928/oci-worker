package com.ociworker.bastion;

import com.ociworker.exception.OciException;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.PanelAuthService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/oci/bastion")
public class BastionController {

    @Resource
    private BastionService bastionService;
    @Resource
    private PanelAuthService panelAuthService;

    @PostMapping("/credentials")
    public ResponseData<?> credentials(@RequestBody Map<String, String> params,
                                       HttpServletRequest httpRequest) {
        authenticatedAccount(httpRequest);
        return ResponseData.ok(bastionService.inspectCredentials(
                params == null ? null : params.get("id"),
                params == null ? null : params.get("instanceId")));
    }

    @PostMapping("/prepare")
    public ResponseData<?> prepare(@RequestBody BastionPrepareRequest request,
                                   HttpServletRequest httpRequest) {
        return ResponseData.ok(bastionService.prepare(request, authenticatedAccount(httpRequest)));
    }

    private String authenticatedAccount(HttpServletRequest request) {
        String account = panelAuthService.authenticatedAccount(request, true, false);
        if (account == null || account.isBlank()) {
            throw new OciException("Panel authentication is required");
        }
        return account;
    }
}
