package com.ociworker.config;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Value("${web.account}")
    private String defaultAccount;
    @Value("${web.password}")
    private String defaultPassword;

    @Resource
    private OciKvMapper kvMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String getKv(String code) {
        try {
            OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getCode, code).eq(OciKv::getType, "sys_config"));
            return kv != null ? kv.getValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getEffectiveAccount() {
        String stored = getKv("web_account");
        return stored != null ? stored : defaultAccount;
    }

    private boolean isHashedPassword(String pwd) {
        return pwd != null && pwd.length() == 64 && pwd.matches("[0-9a-f]+");
    }

    private String getEffectivePasswordHash() {
        String stored = getKv("web_password");
        if (stored != null) {
            if (isHashedPassword(stored)) {
                return stored;
            }
            return DigestUtil.sha256Hex(stored);
        }
        return DigestUtil.sha256Hex(defaultPassword);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        if (uri.startsWith("/api/auth/login")
                || uri.startsWith("/api/auth/needSetup")
                || uri.startsWith("/api/auth/setup")
                || uri.startsWith("/api/auth/tgLogin")
                || uri.startsWith("/api/auth/tgLoginAvailable")
                || uri.startsWith("/ws/")
                || uri.equals("/") || uri.startsWith("/assets/")
                || uri.endsWith(".html") || uri.endsWith(".js")
                || uri.endsWith(".css") || uri.endsWith(".ico")
                || uri.startsWith("/ip-info")) {
            return true;
        }

        String token = request.getHeader("Authorization");
        if (StrUtil.isBlank(token)) {
            token = request.getParameter("token");
        }

        String effectiveAccount = getEffectiveAccount();
        String effectivePwdHash = getEffectivePasswordHash();

        if (StrUtil.isBlank(token) || !CommonUtils.validateToken(token, effectiveAccount, effectivePwdHash)) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            response.getWriter().write(objectMapper.writeValueAsString(ResponseData.error(401, "Unauthorized")));
            return false;
        }
        return true;
    }
}
