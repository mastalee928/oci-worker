package com.ociworker.nlb;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NlbFrontendContractTest {

    @Test
    void nlbIsLastVcnTabAndHasNoTopLevelRoute() throws Exception {
        String manager = Files.readString(Path.of("..", "frontend", "src", "views", "VcnManager.vue"));
        String router = Files.readString(Path.of("..", "frontend", "src", "router", "index.ts"));

        int subnet = manager.indexOf("key=\"subnet\"");
        int igw = manager.indexOf("key=\"igw\"");
        int nat = manager.indexOf("key=\"nat\"");
        int sg = manager.indexOf("key=\"sg\"");
        int lpg = manager.indexOf("key=\"lpg\"");
        int rt = manager.indexOf("key=\"rt\"");
        int sl = manager.indexOf("key=\"sl\"");
        int nlb = manager.indexOf("key=\"nlb\" tab=\"负载均衡器\"");

        assertThat(subnet).isLessThan(igw);
        assertThat(igw).isLessThan(nat);
        assertThat(nat).isLessThan(sg);
        assertThat(sg).isLessThan(lpg);
        assertThat(lpg).isLessThan(rt);
        assertThat(rt).isLessThan(sl);
        assertThat(sl).isLessThan(nlb);
        assertThat(router).doesNotContain("/nlb", "NetworkLoadBalancerPanel");
    }

    @Test
    void independentModuleContainsFullManagementAndNoOracleAiDependency() throws Exception {
        String panel = Files.readString(Path.of("..", "frontend", "src", "modules", "nlb", "NetworkLoadBalancerPanel.vue"));
        String api = Files.readString(Path.of("..", "frontend", "src", "api", "nlb.ts"));

        assertThat(panel).contains(
                "创建负载均衡器", "创建 Listener", "创建 Backend Set", "更新健康检查器",
                "添加 Backend", "迁移区间", "Work Request", "pollWorkRequest",
                "listNlbWorkRequestErrors", "listNlbWorkRequestLogs", "DNS 查询", "TCP 空闲超时");
        assertThat(api).contains(
                "/oci/nlb/create", "/oci/nlb/listener/create", "/oci/nlb/backend-set/create",
                "/oci/nlb/health-checker/update", "/oci/nlb/backend/create", "/oci/nlb/work-request/errors");
        assertThat(panel).doesNotContain("OracleAI", "oracleAi", "loadBalanceService");
    }
}
