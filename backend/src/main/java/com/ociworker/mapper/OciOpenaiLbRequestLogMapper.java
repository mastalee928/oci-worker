package com.ociworker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ociworker.model.entity.OciOpenaiLbRequestLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OciOpenaiLbRequestLogMapper extends BaseMapper<OciOpenaiLbRequestLog> {
    @Delete("""
            DELETE FROM oci_openai_lb_request_log
            WHERE id NOT IN (
                SELECT id FROM (
                    SELECT id FROM oci_openai_lb_request_log
                    ORDER BY create_time DESC
                    LIMIT #{keep}
                ) keep_rows
            )
            """)
    int deleteBeyondLatest(@Param("keep") int keep);
}
