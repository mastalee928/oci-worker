package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.mapper.ScheduledIpExecutionLockMapper;
import com.ociworker.mapper.ScheduledIpRunLogMapper;
import com.ociworker.mapper.ScheduledIpTaskMapper;
import com.ociworker.model.dto.ScheduledIpTaskRequest;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.entity.ScheduledIpTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledIpServiceTest {
    @Mock private ScheduledIpTaskMapper taskMapper;
    @Mock private ScheduledIpRunLogMapper runLogMapper;
    @Mock private ScheduledIpExecutionLockMapper executionLockMapper;
    @Mock private OciUserMapper userMapper;
    @Mock private ScheduledIpRunner runner;

    private ScheduledIpService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledIpService();
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "runLogMapper", runLogMapper);
        ReflectionTestUtils.setField(service, "executionLockMapper", executionLockMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "runner", runner);
        OciUser user = new OciUser();
        user.setId("tenant-1");
        user.setUsername("测试租户");
        lenient().when(userMapper.selectById("tenant-1")).thenReturn(user);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rejectsIntervalsBelowTenMinutes() {
        ScheduledIpTaskRequest request = validRequest();
        request.setIntervalMinutes(9);

        assertThrows(OciException.class, () -> service.create(request));
        verify(taskMapper, never()).insert(any(ScheduledIpTask.class));
    }

    @Test
    void rejectsAnotherEnabledTaskForTheSameInstance() {
        when(taskMapper.selectCount(any())).thenReturn(1L);

        assertThrows(OciException.class, () -> service.create(validRequest()));
        verify(executionLockMapper, never()).claimEnabledTask(any(), any(), any());
    }

    @Test
    void immediateExecutionRunsOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicBoolean executed = new AtomicBoolean(false);

        ScheduledIpService.runAfterCommit(() -> executed.set(true));
        assertFalse(executed.get());

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        assertTrue(executed.get());
    }

    private static ScheduledIpTaskRequest validRequest() {
        ScheduledIpTaskRequest request = new ScheduledIpTaskRequest();
        request.setTenantConfigId("tenant-1");
        request.setRegion("us-ashburn-1");
        request.setInstanceId("ocid1.instance.oc1.iad.test");
        request.setInstanceName("test-instance");
        request.setIntervalMinutes(10);
        request.setDnsEnabled(false);
        return request;
    }
}
