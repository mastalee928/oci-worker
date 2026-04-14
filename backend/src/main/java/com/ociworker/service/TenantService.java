package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.IdListParams;
import com.ociworker.model.params.PageParams;
import com.ociworker.model.params.TenantParams;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Slf4j
@Service
public class TenantService {

    @Resource
    private OciUserMapper userMapper;

    @Value("${oci-cfg.key-dir-path}")
    private String keyDirPath;

    public Page<OciUser> list(PageParams params) {
        Page<OciUser> page = new Page<>(params.getCurrent(), params.getSize());
        LambdaQueryWrapper<OciUser> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(params.getKeyword())) {
            wrapper.and(w -> w
                    .like(OciUser::getUsername, params.getKeyword())
                    .or().like(OciUser::getOciRegion, params.getKeyword())
                    .or().like(OciUser::getOciFingerprint, params.getKeyword()));
        }
        wrapper.orderByDesc(OciUser::getCreateTime);
        return userMapper.selectPage(page, wrapper);
    }

    public void add(TenantParams params) {
        OciUser user = new OciUser();
        user.setId(CommonUtils.generateId());
        user.setUsername(params.getUsername());
        user.setOciTenantId(params.getOciTenantId());
        user.setOciUserId(params.getOciUserId());
        user.setOciFingerprint(params.getOciFingerprint());
        user.setOciRegion(params.getOciRegion());
        user.setOciKeyPath(params.getOciKeyPath());
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);
        log.info("Added tenant config: {}", params.getUsername());
    }

    public void update(TenantParams params) {
        if (StrUtil.isBlank(params.getId())) {
            throw new OciException("ID不能为空");
        }
        OciUser user = userMapper.selectById(params.getId());
        if (user == null) {
            throw new OciException("配置不存在");
        }
        user.setUsername(params.getUsername());
        user.setOciTenantId(params.getOciTenantId());
        user.setOciUserId(params.getOciUserId());
        user.setOciFingerprint(params.getOciFingerprint());
        user.setOciRegion(params.getOciRegion());
        if (StrUtil.isNotBlank(params.getOciKeyPath())) {
            user.setOciKeyPath(params.getOciKeyPath());
        }
        userMapper.updateById(user);
        log.info("Updated tenant config: {}", params.getUsername());
    }

    public void remove(IdListParams params) {
        userMapper.deleteBatchIds(params.getIdList());
        log.info("Removed tenant configs: {}", params.getIdList());
    }

    public OciUser getById(String id) {
        OciUser user = userMapper.selectById(id);
        if (user == null) {
            throw new OciException("配置不存在");
        }
        return user;
    }

    public String uploadKey(MultipartFile file) throws IOException {
        Path dirPath = Path.of(System.getProperty("user.dir"), keyDirPath).normalize();
        File dir = dirPath.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String fileName = CommonUtils.generateId() + ".pem";
        File target = new File(dir, fileName);
        file.transferTo(target);
        log.info("Uploaded key file: {}", target.getAbsolutePath());
        return target.getAbsolutePath();
    }
}
