package com.ociworker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ociworker.model.entity.OciUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OciUserMapper extends BaseMapper<OciUser> {
}
