package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.PageParams;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskSchedulerListTest {

    @Test
    void listReturnsConfiguredInstanceNameForEditForm() {
        OciCreateTaskMapper taskMapper = mock(OciCreateTaskMapper.class);
        OciUserMapper userMapper = mock(OciUserMapper.class);
        OciCreateTask task = new OciCreateTask();
        task.setId("task-1");
        task.setUserId("user-1");
        task.setInstanceName("production-worker");
        task.setCreateNumbers(1);
        OciUser user = new OciUser();
        user.setId("user-1");
        user.setUsername("tenant");
        when(taskMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(invocation -> {
                    Page<OciCreateTask> page = invocation.getArgument(0);
                    page.setRecords(List.of(task));
                    page.setTotal(1);
                    return page;
                });
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user));

        TaskSchedulerService service = new TaskSchedulerService();
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);

        Page<?> result = service.listTasks(new PageParams());

        assertThat(result.getRecords()).singleElement()
                .extracting(row -> ((java.util.Map<?, ?>) row).get("instanceName"))
                .isEqualTo("production-worker");
    }

    @Test
    void emptyFilteredPageDoesNotExecuteEmptyBatchIdQuery() {
        OciCreateTaskMapper taskMapper = mock(OciCreateTaskMapper.class);
        OciUserMapper userMapper = mock(OciUserMapper.class);
        when(taskMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TaskSchedulerService service = new TaskSchedulerService();
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        PageParams params = new PageParams();
        params.setStatus("FAILED");

        Page<?> result = service.listTasks(params);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(userMapper, never()).selectBatchIds(any());
    }
}
