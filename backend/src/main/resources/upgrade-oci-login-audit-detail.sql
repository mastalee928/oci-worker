-- 登录审计扩展详情（JSON）；已有库执行一次。若列已存在会报错，可忽略。
ALTER TABLE oci_login_audit
    ADD COLUMN login_detail LONGTEXT NULL COMMENT 'AES-256-GCM: 登录请求完整详情' AFTER user_agent;
