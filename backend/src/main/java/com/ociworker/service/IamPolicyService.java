package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.Policy;
import com.oracle.bmc.identity.requests.GetPolicyRequest;
import com.oracle.bmc.identity.requests.ListPoliciesRequest;
import com.oracle.bmc.identity.responses.GetPolicyResponse;
import com.oracle.bmc.identity.responses.ListPoliciesResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经典 IAM Policy（Identity API），与 Identity Domain 策略无关。
 *
 * @see <a href="https://docs.oracle.com/en-us/iaas/api/#/en/identity/20160918/Policy/ListPolicies">ListPolicies</a>
 * @see <a href="https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/managingpolicies.htm">Managing Policies</a>
 */
@Slf4j
@Service
public class IamPolicyService {

    @Resource
    private OciUserMapper userMapper;

    private OciClientService buildClient(String tenantId) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");
        return new OciClientService(SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build());
    }

    /**
     * 列出租户下 IAM 策略（根 compartment + 子树，含分页）。
     * 需 API 用户具备 inspect policies 等读权限。
     */
    public Map<String, Object> listPolicies(String tenantId) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");

        String compartmentId = user.getOciTenantId();
        List<Map<String, Object>> items = new ArrayList<>();

        try (OciClientService client = buildClient(tenantId)) {
            IdentityClient identityClient = client.getIdentityClient();
            String page = null;
            do {
                ListPoliciesRequest.Builder req = ListPoliciesRequest.builder()
                        .compartmentId(compartmentId)
                        .compartmentIdInSubtree(true);
                if (page != null) {
                    req.page(page);
                }
                ListPoliciesResponse resp = identityClient.listPolicies(req.build());
                if (resp.getItems() != null) {
                    for (Policy p : resp.getItems()) {
                        items.add(policySummary(p));
                    }
                }
                page = resp.getOpcNextPage();
            } while (page != null && !page.isBlank());
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            log.warn("listPolicies failed for tenant config {}: {}", tenantId, e.getMessage());
            throw new OciException("获取 IAM 策略失败: " + e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compartmentId", compartmentId);
        out.put("items", items);
        out.put("count", items.size());
        return out;
    }

    public Map<String, Object> getPolicy(String tenantId, String policyId) {
        if (policyId == null || policyId.isBlank()) {
            throw new OciException("policyId 不能为空");
        }
        try (OciClientService client = buildClient(tenantId)) {
            GetPolicyResponse resp = client.getIdentityClient().getPolicy(
                    GetPolicyRequest.builder().policyId(policyId).build());
            Policy p = resp.getPolicy();
            if (p == null) throw new OciException("策略不存在");
            Map<String, Object> detail = policySummary(p);
            detail.put("statements", p.getStatements() != null ? p.getStatements() : List.of());
            return detail;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            log.warn("getPolicy {} failed: {}", policyId, e.getMessage());
            throw new OciException("获取策略详情失败: " + e.getMessage());
        }
    }

    private static Map<String, Object> policySummary(Policy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("compartmentId", p.getCompartmentId());
        m.put("lifecycleState", p.getLifecycleState() != null ? p.getLifecycleState().getValue() : null);
        List<String> stmts = p.getStatements();
        m.put("statementCount", stmts != null ? stmts.size() : 0);
        m.put("timeCreated", p.getTimeCreated());
        return m;
    }
}
