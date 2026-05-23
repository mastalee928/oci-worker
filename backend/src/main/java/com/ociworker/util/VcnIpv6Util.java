package com.ociworker.util;

import cn.hutool.core.util.StrUtil;
import com.oracle.bmc.core.VirtualNetwork;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.GetSubnetRequest;
import com.oracle.bmc.core.requests.GetVcnRequest;

import java.util.List;

/** VCN 是否已分配 IPv6 CIDR（OCI 允许安全列表使用 ::/0 的前提）。 */
public final class VcnIpv6Util {

    private VcnIpv6Util() {}

    public static boolean isEnabled(Vcn vcn) {
        if (vcn == null) {
            return false;
        }
        List<String> blocks = vcn.getIpv6CidrBlocks();
        return blocks != null && !blocks.isEmpty();
    }

    public static boolean isEnabled(VirtualNetwork client, Subnet subnet) {
        if (subnet == null || client == null) {
            return false;
        }
        return isEnabled(client, subnet.getVcnId());
    }

    public static boolean isEnabled(VirtualNetwork client, String vcnId) {
        if (client == null || StrUtil.isBlank(vcnId)) {
            return false;
        }
        Vcn vcn = client.getVcn(GetVcnRequest.builder().vcnId(vcnId.trim()).build()).getVcn();
        return isEnabled(vcn);
    }

    public static boolean isEnabledForSubnet(VirtualNetwork client, String subnetId) {
        if (client == null || StrUtil.isBlank(subnetId)) {
            return false;
        }
        Subnet subnet = client.getSubnet(GetSubnetRequest.builder().subnetId(subnetId.trim()).build()).getSubnet();
        return isEnabled(client, subnet);
    }
}
