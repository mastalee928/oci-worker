package com.ociworker.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.mapper.WebSshConnectionBookmarkMapper;
import com.ociworker.mapper.WebSshScriptBookmarkMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.entity.WebSshConnectionBookmark;
import com.ociworker.model.entity.WebSshScriptBookmark;
import com.ociworker.util.CommonUtils;
import com.ociworker.webssh.WebSshBookmarkDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class WebSshBookmarkService {

    static final int MAX_CONNECTION_BOOKMARKS = 200;
    static final int MAX_SCRIPT_BOOKMARKS = 200;
    static final int MAX_HOSTNAME_LENGTH = 255;
    static final int MAX_USERNAME_LENGTH = 128;
    static final int MAX_SCRIPT_NAME_LENGTH = 128;
    static final int MAX_COMMAND_LENGTH = 16_384;

    private static final String CRYPTO_MARKER_CODE = "security_webssh_bookmark_encrypted_v1";
    private static final String CRYPTO_MARKER_TYPE = "sys_migration";

    private final WebSshConnectionBookmarkMapper connectionMapper;
    private final WebSshScriptBookmarkMapper scriptMapper;
    private final OciKvMapper kvMapper;
    private final WebSshBookmarkCryptoService cryptoService;

    private volatile boolean cryptoBindingVerified;

    public WebSshBookmarkService(WebSshConnectionBookmarkMapper connectionMapper,
                                 WebSshScriptBookmarkMapper scriptMapper,
                                 OciKvMapper kvMapper,
                                 WebSshBookmarkCryptoService cryptoService) {
        this.connectionMapper = connectionMapper;
        this.scriptMapper = scriptMapper;
        this.kvMapper = kvMapper;
        this.cryptoService = cryptoService;
    }

    @Transactional
    public WebSshBookmarkDto.Bundle list() {
        requireCryptoReady();
        return bundle();
    }

    @Transactional
    public WebSshBookmarkDto.Bundle saveConnection(WebSshBookmarkDto.ConnectionInput input) {
        requireCryptoReady();
        ConnectionValue value = normalizeConnection(input);
        upsertConnection(value);
        return bundle();
    }

    @Transactional
    public WebSshBookmarkDto.Bundle deleteConnection(String id) {
        requireCryptoReady();
        deleteById(id, true);
        return bundle();
    }

    @Transactional
    public WebSshBookmarkDto.Bundle saveScript(WebSshBookmarkDto.ScriptInput input) {
        requireCryptoReady();
        ScriptValue value = normalizeScript(input);
        if (scriptMapper.selectCount(null) >= MAX_SCRIPT_BOOKMARKS) {
            throw new OciException("脚本书签数量已达到上限（" + MAX_SCRIPT_BOOKMARKS + "）");
        }
        insertScript(value);
        return bundle();
    }

    @Transactional
    public WebSshBookmarkDto.Bundle deleteScript(String id) {
        requireCryptoReady();
        deleteById(id, false);
        return bundle();
    }

    /** 合并浏览器旧缓存，服务端按连接键和脚本内容去重。 */
    @Transactional
    public WebSshBookmarkDto.Bundle migrate(WebSshBookmarkDto.MigrationRequest request) {
        requireCryptoReady();
        List<WebSshBookmarkDto.ConnectionInput> connections = request == null || request.connections() == null
                ? List.of() : request.connections();
        List<WebSshBookmarkDto.ScriptInput> scripts = request == null || request.scripts() == null
                ? List.of() : request.scripts();
        if (connections.size() > MAX_CONNECTION_BOOKMARKS || scripts.size() > MAX_SCRIPT_BOOKMARKS) {
            throw new OciException("书签数量超过允许上限");
        }

        List<ConnectionValue> normalizedConnections = connections.stream()
                .map(this::normalizeConnection).toList();
        Set<String> existingConnectionKeys = new HashSet<>(connectionMapper.selectList(null).stream()
                .map(WebSshConnectionBookmark::getDedupeKey).toList());
        Set<String> incomingConnectionKeys = new HashSet<>();
        for (ConnectionValue value : normalizedConnections) {
            if (incomingConnectionKeys.add(value.dedupeKey())) {
                if (!existingConnectionKeys.contains(value.dedupeKey())) {
                    upsertConnection(value);
                    existingConnectionKeys.add(value.dedupeKey());
                } else {
                    upsertConnection(value);
                }
            }
        }

        List<ScriptValue> normalizedScripts = scripts.stream().map(this::normalizeScript).toList();
        List<WebSshScriptBookmark> existingRows = scriptMapper.selectList(null);
        Set<String> scriptKeys = new HashSet<>();
        for (WebSshScriptBookmark row : existingRows) {
            scriptKeys.add(scriptKey(row.getName(), cryptoService.decryptIfEncrypted(
                    row.getCommandEncrypted(), row.getId())));
        }
        Set<String> incomingScriptKeys = new HashSet<>();
        Set<String> keysToInsert = new HashSet<>();
        for (ScriptValue value : normalizedScripts) {
            if (incomingScriptKeys.add(value.dedupeKey()) && !scriptKeys.contains(value.dedupeKey())) {
                keysToInsert.add(value.dedupeKey());
            }
        }
        if (existingRows.size() + keysToInsert.size() > MAX_SCRIPT_BOOKMARKS) {
            throw new OciException("迁移后脚本书签数量超过上限（" + MAX_SCRIPT_BOOKMARKS + "）");
        }
        for (ScriptValue value : normalizedScripts) {
            if (keysToInsert.remove(value.dedupeKey())) {
                insertScript(value);
            }
        }
        return bundle();
    }

    /** BackupService 在创建/恢复 ZIP 前调用，确认数据库标记与 keys 密钥一致。 */
    @Transactional
    public synchronized void requireCryptoReady() {
        cryptoService.requireReady();
        if (cryptoBindingVerified) {
            return;
        }
        OciKv marker = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, CRYPTO_MARKER_CODE)
                .eq(OciKv::getType, CRYPTO_MARKER_TYPE));
        if (marker != null) {
            cryptoService.requireKeyFingerprint(marker.getValue());
        } else {
            OciKv created = new OciKv();
            created.setId(CommonUtils.generateId());
            created.setCode(CRYPTO_MARKER_CODE);
            created.setValue(cryptoService.currentKeyFingerprint());
            created.setType(CRYPTO_MARKER_TYPE);
            created.setCreateTime(LocalDateTime.now());
            kvMapper.insert(created);
        }
        cryptoBindingVerified = true;
    }

    public synchronized void resetCryptoVerification() {
        cryptoBindingVerified = false;
    }

    private WebSshBookmarkDto.Bundle bundle() {
        requireCryptoReady();
        List<WebSshBookmarkDto.ConnectionView> connections = connectionMapper.selectList(
                        new LambdaQueryWrapper<WebSshConnectionBookmark>()
                                .orderByAsc(WebSshConnectionBookmark::getSortOrder)
                                .orderByAsc(WebSshConnectionBookmark::getCreateTime)
                                .orderByAsc(WebSshConnectionBookmark::getId))
                .stream()
                .map(row -> new WebSshBookmarkDto.ConnectionView(row.getId(), row.getHostname(),
                        row.getPort(), row.getUsername(), row.getAuthType()))
                .toList();

        List<WebSshBookmarkDto.ScriptView> scripts = scriptMapper.selectList(
                        new LambdaQueryWrapper<WebSshScriptBookmark>()
                                .orderByAsc(WebSshScriptBookmark::getSortOrder)
                                .orderByAsc(WebSshScriptBookmark::getCreateTime)
                                .orderByAsc(WebSshScriptBookmark::getId))
                .stream()
                .map(row -> new WebSshBookmarkDto.ScriptView(row.getId(), row.getName(),
                        cryptoService.decryptIfEncrypted(row.getCommandEncrypted(), row.getId())))
                .toList();
        return new WebSshBookmarkDto.Bundle(connections, scripts);
    }

    private void upsertConnection(ConnectionValue value) {
        WebSshConnectionBookmark row = connectionMapper.selectOne(new LambdaQueryWrapper<WebSshConnectionBookmark>()
                .eq(WebSshConnectionBookmark::getDedupeKey, value.dedupeKey()));
        LocalDateTime now = LocalDateTime.now();
        if (row != null) {
            row.setHostname(value.hostname());
            row.setPort(value.port());
            row.setUsername(value.username());
            row.setAuthType(value.authType());
            row.setUpdateTime(now);
            connectionMapper.updateById(row);
            return;
        }
        if (connectionMapper.selectCount(null) >= MAX_CONNECTION_BOOKMARKS) {
            throw new OciException("连接书签数量已达到上限（" + MAX_CONNECTION_BOOKMARKS + "）");
        }
        row = new WebSshConnectionBookmark();
        row.setId(CommonUtils.generateId());
        row.setDedupeKey(value.dedupeKey());
        row.setHostname(value.hostname());
        row.setPort(value.port());
        row.setUsername(value.username());
        row.setAuthType(value.authType());
        row.setSortOrder(nextConnectionSortOrder());
        row.setCreateTime(now);
        row.setUpdateTime(now);
        connectionMapper.insert(row);
    }

    private void insertScript(ScriptValue value) {
        WebSshScriptBookmark row = new WebSshScriptBookmark();
        row.setId(CommonUtils.generateId());
        row.setName(value.name());
        row.setCommandEncrypted(cryptoService.encrypt(value.command(), row.getId()));
        row.setSortOrder(nextScriptSortOrder());
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(row.getCreateTime());
        scriptMapper.insert(row);
    }

    private long nextConnectionSortOrder() {
        WebSshConnectionBookmark last = connectionMapper.selectOne(new LambdaQueryWrapper<WebSshConnectionBookmark>()
                .orderByDesc(WebSshConnectionBookmark::getSortOrder).last("LIMIT 1"));
        return last == null || last.getSortOrder() == null ? 1L : last.getSortOrder() + 1L;
    }

    private long nextScriptSortOrder() {
        WebSshScriptBookmark last = scriptMapper.selectOne(new LambdaQueryWrapper<WebSshScriptBookmark>()
                .orderByDesc(WebSshScriptBookmark::getSortOrder).last("LIMIT 1"));
        return last == null || last.getSortOrder() == null ? 1L : last.getSortOrder() + 1L;
    }

    private void deleteById(String id, boolean connection) {
        if (!StringUtils.hasText(id) || !id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new OciException("书签 ID 无效");
        }
        if (connection) {
            connectionMapper.deleteById(id);
        } else {
            scriptMapper.deleteById(id);
        }
    }

    private ConnectionValue normalizeConnection(WebSshBookmarkDto.ConnectionInput input) {
        if (input == null) {
            throw new OciException("连接书签不能为空");
        }
        String hostname = normalizeHost(input.hostname());
        int port = input.port() == null ? 22 : input.port();
        if (port < 1 || port > 65535) {
            throw new OciException("端口必须是 1 至 65535");
        }
        String username = normalizeText(input.username(), "用户名", MAX_USERNAME_LENGTH, true);
        if (containsControlOrWhitespace(username)) {
            throw new OciException("用户名格式无效");
        }
        String authType = StringUtils.hasText(input.authType()) ? input.authType().trim().toLowerCase(Locale.ROOT)
                : "password";
        if (!authType.equals("password") && !authType.equals("key")) {
            throw new OciException("认证类型无效");
        }
        String dedupeKey = DigestUtil.sha256Hex(hostname + '\u0000' + port + '\u0000' + username);
        return new ConnectionValue(hostname, port, username, authType, dedupeKey);
    }

    private ScriptValue normalizeScript(WebSshBookmarkDto.ScriptInput input) {
        if (input == null) {
            throw new OciException("脚本书签不能为空");
        }
        String name = normalizeText(input.name(), "脚本名称", MAX_SCRIPT_NAME_LENGTH, false);
        if (!StringUtils.hasText(input.cmd())) {
            throw new OciException("脚本命令不能为空");
        }
        String command = input.cmd().strip();
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new OciException("脚本命令不能超过 " + MAX_COMMAND_LENGTH + " 个字符");
        }
        if (command.indexOf('\u0000') >= 0) {
            throw new OciException("脚本命令包含无效字符");
        }
        return new ScriptValue(name, command, scriptKey(name, command));
    }

    private String normalizeHost(String value) {
        if (!StringUtils.hasText(value)) {
            throw new OciException("主机地址不能为空");
        }
        String host = value.strip().toLowerCase(Locale.ROOT);
        if (host.length() > MAX_HOSTNAME_LENGTH || containsControlOrWhitespace(host)) {
            throw new OciException("主机地址格式无效或过长");
        }
        return host;
    }

    private String normalizeText(String value, String label, int maxLength, boolean defaultRoot) {
        String text = StringUtils.hasText(value) ? value.strip() : (defaultRoot ? "root" : "");
        if (!StringUtils.hasText(text)) {
            throw new OciException(label + "不能为空");
        }
        if (text.length() > maxLength || containsControl(text)) {
            throw new OciException(label + "格式无效或过长");
        }
        return text;
    }

    private static boolean containsControlOrWhitespace(String value) {
        return value.chars().anyMatch(ch -> Character.isISOControl(ch) || Character.isWhitespace(ch));
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static String scriptKey(String name, String command) {
        return DigestUtil.sha256Hex(name + '\u0000' + command);
    }

    private record ConnectionValue(String hostname, int port, String username, String authType, String dedupeKey) {
    }

    private record ScriptValue(String name, String command, String dedupeKey) {
    }
}
