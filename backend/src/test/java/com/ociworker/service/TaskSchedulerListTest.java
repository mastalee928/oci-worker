package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.params.PageParams;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskSchedulerListTest {

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
