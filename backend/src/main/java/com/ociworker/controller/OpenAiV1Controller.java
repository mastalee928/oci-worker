package com.ociworker.controller;

import com.ociworker.config.OpenAiApiConstants;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciUser;
import com.ociworker.service.OciGenerativeOpenAiService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Controller
public class OpenAiV1Controller {

    @Resource
    private OciGenerativeOpenAiService generativeOpenAiService;
    @Resource
    private OciUserMapper ociUserMapper;

    @RequestMapping(
            value = {"/v1", "/v1/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD})
    public void v1Proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String id = (String) request.getAttribute(OpenAiApiConstants.ATTR_TENANT_USER_ID);
        if (id == null) {
            response.setStatus(401);
            return;
        }
        OciUser u = ociUserMapper.selectById(id);
        if (u == null) {
            response.setStatus(403);
            return;
        }
        try {
            generativeOpenAiService.proxy(u, request, response);
        } catch (OciException e) {
            error(response, 502, e.getMessage() != null ? e.getMessage() : "OCI 错误");
        } catch (IOException e) {
            if (!response.isCommitted() && (e.getMessage() == null
                    || !e.getMessage().toLowerCase().contains("broken")
                    && !e.getMessage().toLowerCase().contains("aborted"))) {
                error(response, 502, e.getMessage() != null ? e.getMessage() : "转发出错");
            }
        } catch (Exception e) {
            error(response, 500, e.getMessage() != null ? e.getMessage() : "internal_error");
        }
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        String safe = (message == null) ? "" : message.replace("\\", "\\\\").replace("\"", "\\'");
        response.getOutputStream().write(
                String.format("{\"error\":{\"type\":\"oci_error\",\"message\":\"%s\"}}", safe).getBytes(StandardCharsets.UTF_8));
    }
}
