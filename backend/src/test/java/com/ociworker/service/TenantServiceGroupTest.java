package com.ociworker.service;

import com.ociworker.mapper.OciKvMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.entity.OciUser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantServiceGroupTest {

    @Test
    @SuppressWarnings("unchecked")
    void loadsAllGroupOrdersFromSingleKvQuery() {
        TenantService service = new TenantService();
        OciUserMapper userMapper = mock(OciUserMapper.class);
        OciKvMapper kvMapper = mock(OciKvMapper.class);

        OciUser first = new OciUser();
        first.setGroupLevel1("生产");
        first.setGroupLevel2("香港");
        OciUser second = new OciUser();
        second.setGroupLevel1("生产");
        second.setGroupLevel2("东京");

        OciKv level1Order = groupKv("group_order_l1", "生产");
        OciKv level2Order = groupKv("group_order_l2:生产", "东京,香港");
        when(userMapper.selectList(any())).thenReturn(List.of(first, second));
        when(kvMapper.selectList(any())).thenReturn(List.of(level1Order, level2Order));

        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "kvMapper", kvMapper);

        Map<String, Object> groups = service.getDistinctGroups();

        assertEquals(List.of("生产"), groups.get("level1"));
        Map<String, List<String>> level2 = (Map<String, List<String>>) groups.get("level2");
        assertEquals(List.of("东京", "香港"), level2.get("生产"));
        verify(kvMapper).selectList(any());
        verify(kvMapper, never()).selectOne(any());
    }

    private static OciKv groupKv(String code, String value) {
        OciKv kv = new OciKv();
        kv.setCode(code);
        kv.setValue(value);
        kv.setType("group");
        return kv;
    }
}
