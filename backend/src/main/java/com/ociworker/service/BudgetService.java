package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.budget.BudgetClient;
import com.oracle.bmc.budget.model.AlertRule;
import com.oracle.bmc.budget.model.AlertRuleSummary;
import com.oracle.bmc.budget.model.AlertType;
import com.oracle.bmc.budget.model.Budget;
import com.oracle.bmc.budget.model.BudgetSummary;
import com.oracle.bmc.budget.model.CreateAlertRuleDetails;
import com.oracle.bmc.budget.model.CreateBudgetDetails;
import com.oracle.bmc.budget.model.ProcessingPeriodType;
import com.oracle.bmc.budget.model.ResetPeriod;
import com.oracle.bmc.budget.model.TargetType;
import com.oracle.bmc.budget.model.ThresholdType;
import com.oracle.bmc.budget.model.UpdateAlertRuleDetails;
import com.oracle.bmc.budget.model.UpdateBudgetDetails;
import com.oracle.bmc.budget.requests.CreateAlertRuleRequest;
import com.oracle.bmc.budget.requests.CreateBudgetRequest;
import com.oracle.bmc.budget.requests.DeleteAlertRuleRequest;
import com.oracle.bmc.budget.requests.DeleteBudgetRequest;
import com.oracle.bmc.budget.requests.ListAlertRulesRequest;
import com.oracle.bmc.budget.requests.ListBudgetsRequest;
import com.oracle.bmc.budget.requests.UpdateAlertRuleRequest;
import com.oracle.bmc.budget.requests.UpdateBudgetRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class BudgetService {

    @Resource
    private OciUserMapper userMapper;

    public Map<String, Object> listBudgets(String userId) {
        OciUser user = requireUser(userId);
        try (OciClientService client = new OciClientService(buildDto(user));
             BudgetClient budgetClient = buildBudgetClient(client, user)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            String page = null;
            do {
                var resp = budgetClient.listBudgets(ListBudgetsRequest.builder()
                        .compartmentId(user.getOciTenantId())
                        .limit(100)
                        .page(page)
                        .build());
                for (BudgetSummary b : resp.getItems()) {
                    Map<String, Object> row = budgetRow(b);
                    try {
                        row.put("alertRules", listAlertRuleRows(budgetClient, b.getId()));
                    } catch (Exception e) {
                        log.warn("Failed to list alert rules for budget {}: {}", b.getId(), e.getMessage());
                        row.put("alertRules", new ArrayList<>());
                        row.put("alertRulesError", e.getMessage());
                    }
                    rows.add(row);
                }
                page = resp.getOpcNextPage();
            } while (page != null);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", rows);
            out.put("compartmentId", user.getOciTenantId());
            out.put("region", user.getOciRegion());
            out.put("links", Map.of(
                    "budgets", "https://cloud.oracle.com/billing/budgets?region=" + user.getOciRegion(),
                    "costAnalysis", "https://cloud.oracle.com/billing/cost-analysis?region=" + user.getOciRegion()));
            return out;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "获取成本预算失败: " + safeMessage(e));
        }
    }

    public Map<String, Object> createBudget(String userId, Map<String, Object> params) {
        OciUser user = requireUser(userId);
        try (OciClientService client = new OciClientService(buildDto(user));
             BudgetClient budgetClient = buildBudgetClient(client, user)) {
            CreateBudgetDetails details = buildCreateBudgetDetails(user, params);
            Budget budget = budgetClient.createBudget(CreateBudgetRequest.builder()
                    .opcRetryToken(UUID.randomUUID().toString())
                    .createBudgetDetails(details)
                    .build()).getBudget();
            Map<String, Object> row = budgetRow(budget);
            row.put("alertRules", listAlertRuleRows(budgetClient, budget.getId()));
            return row;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "创建成本预算失败: " + safeMessage(e));
        }
    }

    public Map<String, Object> updateBudget(String userId, Map<String, Object> params) {
        OciUser user = requireUser(userId);
        String budgetId = requiredString(params, "budgetId", "budgetId 不能为空");
        try (OciClientService client = new OciClientService(buildDto(user));
             BudgetClient budgetClient = buildBudgetClient(client, user)) {
            Budget budget = budgetClient.updateBudget(UpdateBudgetRequest.builder()
                    .budgetId(budgetId)
                    .updateBudgetDetails(buildUpdateBudgetDetails(params))
                    .build()).getBudget();
            Map<String, Object> row = budgetRow(budget);
            row.put("alertRules", listAlertRuleRows(budgetClient, budget.getId()));
            return row;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "更新成本预算失败: " + safeMessage(e));
        }
    }

    public void deleteBudget(String userId, String budgetId) {
        OciUser user = requireUser(userId);
        if (StrUtil.isBlank(budgetId)) throw new OciException("budgetId 不能为空");
        try (OciClientService client = new OciClientService(buildDto(user));
             BudgetClient budgetClient = buildBudgetClient(client, user)) {
            budgetClient.deleteBudget(DeleteBudgetRequest.builder().budgetId(budgetId).build());
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "删除成本预算失败: " + safeMessage(e));
        }
    }

    public List<Map<String, Object>> listAlertRules(String userId, String budgetId) {
        OciUser user = requireUser(userId);
        if (StrUtil.isBlank(budgetId)) throw new OciException("budgetId 不能为空");
        try (OciClientService client = new OciClientService(buildDto(user));
             BudgetClient budgetClient = buildBudgetClient(client, user)) {
            return listAlertRuleRows(budgetClient, budgetId);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "获取预算告警规则失败: " + safeMessage(e));
        }
    }

    public Map<String, Object> createAlertRule(String userId, Map<String, Object> params) {
        OciUser user = requireUser(userId);
        String budgetId = requiredString(params, "budgetId", "budgetId 不能为空");
        try (OciClientService client = new OciClientService(buildDto(user));
             BudgetClient budgetClient = buildBudgetClient(client, user)) {
            AlertRule rule = budgetClient.createAlertRule(CreateAlertRuleRequest.builder()
                    .budgetId(budgetId)
                    .opcRetryToken(UUID.randomUUID().toString())
                    .createAlertRuleDetails(buildCreateAlertRuleDetails(params))
                    .build()).getAlertRule();
            return alertRuleRow(rule);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "创建预算告警规则失败: " + safeMessage(e));
        }
    }

    public Map<String, Object> updateAlertRule(String userId, Map<String, Object> params) {
        OciUser user = requireUser(userId);
        String budgetId = requiredString(params, "budgetId", "budgetId 不能为空");
        String alertRuleId = requiredString(params, "alertRuleId", "alertRuleId 不能为空");
        try (OciClientService client = new OciClientService(buildDto(user));
             BudgetClient budgetClient = buildBudgetClient(client, user)) {
            AlertRule rule = budgetClient.updateAlertRule(UpdateAlertRuleRequest.builder()
                    .budgetId(budgetId)
                    .alertRuleId(alertRuleId)
                    .updateAlertRuleDetails(buildUpdateAlertRuleDetails(params))
                    .build()).getAlertRule();
            return alertRuleRow(rule);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "更新预算告警规则失败: " + safeMessage(e));
        }
    }

    public void deleteAlertRule(String userId, String budgetId, String alertRuleId) {
        OciUser user = requireUser(userId);
        if (StrUtil.isBlank(budgetId)) throw new OciException("budgetId 不能为空");
        if (StrUtil.isBlank(alertRuleId)) throw new OciException("alertRuleId 不能为空");
        try (OciClientService client = new OciClientService(buildDto(user));
             BudgetClient budgetClient = buildBudgetClient(client, user)) {
            budgetClient.deleteAlertRule(DeleteAlertRuleRequest.builder()
                    .budgetId(budgetId)
                    .alertRuleId(alertRuleId)
                    .build());
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "删除预算告警规则失败: " + safeMessage(e));
        }
    }

    private CreateBudgetDetails buildCreateBudgetDetails(OciUser user, Map<String, Object> params) {
        String displayName = requiredString(params, "displayName", "预算名称不能为空");
        String compartmentId = optionalString(params, "compartmentId");
        if (compartmentId == null) compartmentId = user.getOciTenantId();
        TargetType targetType = parseTargetType(optionalString(params, "targetType"));
        List<String> targets = parseTargets(params, targetType, user);

        CreateBudgetDetails.Builder builder = CreateBudgetDetails.builder()
                .compartmentId(compartmentId)
                .displayName(displayName)
                .amount(requiredPositiveDecimal(params, "amount", "预算金额必须大于 0"))
                .resetPeriod(parseResetPeriod(optionalString(params, "resetPeriod")))
                .processingPeriodType(parseProcessingPeriodType(optionalString(params, "processingPeriodType")))
                .targetType(targetType)
                .targets(targets);
        applyOptionalBudgetFields(builder, params);
        return builder.build();
    }

    private UpdateBudgetDetails buildUpdateBudgetDetails(Map<String, Object> params) {
        UpdateBudgetDetails.Builder builder = UpdateBudgetDetails.builder();
        if (hasValue(params, "displayName")) builder.displayName(requiredString(params, "displayName", "预算名称不能为空"));
        if (hasValue(params, "description")) builder.description(optionalString(params, "description"));
        if (hasValue(params, "amount")) builder.amount(requiredPositiveDecimal(params, "amount", "预算金额必须大于 0"));
        if (hasValue(params, "resetPeriod")) builder.resetPeriod(parseResetPeriod(optionalString(params, "resetPeriod")));
        if (hasValue(params, "processingPeriodType")) builder.processingPeriodType(parseProcessingPeriodType(optionalString(params, "processingPeriodType")));
        if (hasValue(params, "budgetProcessingPeriodStartOffset")) {
            builder.budgetProcessingPeriodStartOffset(optionalInt(params, "budgetProcessingPeriodStartOffset"));
        }
        if (hasValue(params, "startDate")) builder.startDate(parseDate(params.get("startDate")));
        if (hasValue(params, "endDate")) builder.endDate(parseDate(params.get("endDate")));
        return builder.build();
    }

    private static void applyOptionalBudgetFields(CreateBudgetDetails.Builder builder, Map<String, Object> params) {
        String description = optionalString(params, "description");
        if (description != null) builder.description(description);
        Integer offset = optionalInt(params, "budgetProcessingPeriodStartOffset");
        if (offset != null) builder.budgetProcessingPeriodStartOffset(offset);
        Date startDate = parseDate(params.get("startDate"));
        if (startDate != null) builder.startDate(startDate);
        Date endDate = parseDate(params.get("endDate"));
        if (endDate != null) builder.endDate(endDate);
    }

    private CreateAlertRuleDetails buildCreateAlertRuleDetails(Map<String, Object> params) {
        CreateAlertRuleDetails.Builder builder = CreateAlertRuleDetails.builder()
                .displayName(requiredString(params, "displayName", "告警名称不能为空"))
                .type(parseAlertType(optionalString(params, "type")))
                .threshold(requiredPositiveDecimal(params, "threshold", "告警阈值必须大于 0"))
                .thresholdType(parseThresholdType(optionalString(params, "thresholdType")))
                .recipients(requiredString(params, "recipients", "告警接收人不能为空"));
        applyOptionalAlertFields(builder, params);
        return builder.build();
    }

    private UpdateAlertRuleDetails buildUpdateAlertRuleDetails(Map<String, Object> params) {
        UpdateAlertRuleDetails.Builder builder = UpdateAlertRuleDetails.builder();
        if (hasValue(params, "displayName")) builder.displayName(requiredString(params, "displayName", "告警名称不能为空"));
        if (hasValue(params, "description")) builder.description(optionalString(params, "description"));
        if (hasValue(params, "type")) builder.type(parseAlertType(optionalString(params, "type")));
        if (hasValue(params, "threshold")) builder.threshold(requiredPositiveDecimal(params, "threshold", "告警阈值必须大于 0"));
        if (hasValue(params, "thresholdType")) builder.thresholdType(parseThresholdType(optionalString(params, "thresholdType")));
        if (hasValue(params, "recipients")) builder.recipients(requiredString(params, "recipients", "告警接收人不能为空"));
        if (hasValue(params, "message")) builder.message(optionalString(params, "message"));
        return builder.build();
    }

    private static void applyOptionalAlertFields(CreateAlertRuleDetails.Builder builder, Map<String, Object> params) {
        String description = optionalString(params, "description");
        if (description != null) builder.description(description);
        String message = optionalString(params, "message");
        if (message != null) builder.message(message);
    }

    private List<Map<String, Object>> listAlertRuleRows(BudgetClient budgetClient, String budgetId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String page = null;
        do {
            var resp = budgetClient.listAlertRules(ListAlertRulesRequest.builder()
                    .budgetId(budgetId)
                    .limit(100)
                    .page(page)
                    .build());
            for (AlertRuleSummary r : resp.getItems()) {
                rows.add(alertRuleRow(r));
            }
            page = resp.getOpcNextPage();
        } while (page != null);
        return rows;
    }

    private Map<String, Object> budgetRow(BudgetSummary b) {
        return budgetRow(
                b.getId(), b.getCompartmentId(), b.getTargetCompartmentId(), b.getDisplayName(), b.getDescription(),
                b.getAmount(), b.getResetPeriod(), b.getBudgetProcessingPeriodStartOffset(), b.getProcessingPeriodType(),
                b.getStartDate(), b.getEndDate(), b.getTargetType(), b.getTargets(), b.getLifecycleState(),
                b.getAlertRuleCount(), b.getVersion(), b.getActualSpend(), b.getForecastedSpend(),
                b.getTimeSpendComputed(), b.getTimeCreated(), b.getTimeUpdated());
    }

    private Map<String, Object> budgetRow(Budget b) {
        return budgetRow(
                b.getId(), b.getCompartmentId(), b.getTargetCompartmentId(), b.getDisplayName(), b.getDescription(),
                b.getAmount(), b.getResetPeriod(), b.getBudgetProcessingPeriodStartOffset(), b.getProcessingPeriodType(),
                b.getStartDate(), b.getEndDate(), b.getTargetType(), b.getTargets(), b.getLifecycleState(),
                b.getAlertRuleCount(), b.getVersion(), b.getActualSpend(), b.getForecastedSpend(),
                b.getTimeSpendComputed(), b.getTimeCreated(), b.getTimeUpdated());
    }

    private Map<String, Object> budgetRow(String id, String compartmentId, String targetCompartmentId, String displayName,
                                          String description, BigDecimal amount, ResetPeriod resetPeriod,
                                          Integer offset, ProcessingPeriodType processingPeriodType, Date startDate,
                                          Date endDate, TargetType targetType, List<String> targets, Object lifecycleState,
                                          Integer alertRuleCount, Integer version, BigDecimal actualSpend,
                                          BigDecimal forecastedSpend, Date timeSpendComputed, Date timeCreated,
                                          Date timeUpdated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("compartmentId", compartmentId);
        m.put("targetCompartmentId", targetCompartmentId);
        m.put("displayName", displayName);
        m.put("description", description);
        m.put("amount", decimalString(amount));
        m.put("resetPeriod", enumValue(resetPeriod));
        m.put("budgetProcessingPeriodStartOffset", offset);
        m.put("processingPeriodType", enumValue(processingPeriodType));
        m.put("startDate", dateString(startDate));
        m.put("endDate", dateString(endDate));
        m.put("targetType", enumValue(targetType));
        m.put("targets", targets != null ? targets : List.of());
        m.put("lifecycleState", enumValue(lifecycleState));
        m.put("alertRuleCount", alertRuleCount);
        m.put("version", version);
        m.put("actualSpend", decimalString(actualSpend));
        m.put("forecastedSpend", decimalString(forecastedSpend));
        m.put("actualPercent", percentString(actualSpend, amount));
        m.put("forecastPercent", percentString(forecastedSpend, amount));
        m.put("timeSpendComputed", dateString(timeSpendComputed));
        m.put("timeCreated", dateString(timeCreated));
        m.put("timeUpdated", dateString(timeUpdated));
        return m;
    }

    private Map<String, Object> alertRuleRow(AlertRuleSummary r) {
        return alertRuleRow(r.getId(), r.getBudgetId(), r.getDisplayName(), r.getType(), r.getThreshold(),
                r.getThresholdType(), r.getLifecycleState(), r.getMessage(), r.getDescription(), r.getVersion(),
                r.getRecipients(), r.getTimeCreated(), r.getTimeUpdated());
    }

    private Map<String, Object> alertRuleRow(AlertRule r) {
        return alertRuleRow(r.getId(), r.getBudgetId(), r.getDisplayName(), r.getType(), r.getThreshold(),
                r.getThresholdType(), r.getLifecycleState(), r.getMessage(), r.getDescription(), r.getVersion(),
                r.getRecipients(), r.getTimeCreated(), r.getTimeUpdated());
    }

    private Map<String, Object> alertRuleRow(String id, String budgetId, String displayName, AlertType type,
                                             BigDecimal threshold, ThresholdType thresholdType, Object lifecycleState,
                                             String message, String description, Integer version, String recipients,
                                             Date timeCreated, Date timeUpdated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("budgetId", budgetId);
        m.put("displayName", displayName);
        m.put("type", enumValue(type));
        m.put("threshold", decimalString(threshold));
        m.put("thresholdType", enumValue(thresholdType));
        m.put("lifecycleState", enumValue(lifecycleState));
        m.put("message", message);
        m.put("description", description);
        m.put("version", version);
        m.put("recipients", recipients);
        m.put("timeCreated", dateString(timeCreated));
        m.put("timeUpdated", dateString(timeUpdated));
        return m;
    }

    private BudgetClient buildBudgetClient(OciClientService client, OciUser user) {
        BudgetClient budgetClient = BudgetClient.builder().build(client.getProvider());
        if (StrUtil.isNotBlank(user.getOciRegion())) {
            budgetClient.setRegion(user.getOciRegion());
        }
        return budgetClient;
    }

    private OciUser requireUser(String userId) {
        if (StrUtil.isBlank(userId)) throw new OciException("租户 ID 不能为空");
        OciUser user = userMapper.selectById(userId);
        if (user == null) throw new OciException("租户配置不存在");
        return user;
    }

    private SysUserDTO buildDto(OciUser user) {
        return SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();
    }

    private static TargetType parseTargetType(String raw) {
        String v = normalizeEnumInput(raw, "COMPARTMENT");
        return switch (v) {
            case "COMPARTMENT" -> TargetType.Compartment;
            case "TAG" -> TargetType.Tag;
            default -> throw new OciException("预算目标类型仅支持 COMPARTMENT 或 TAG");
        };
    }

    private static ResetPeriod parseResetPeriod(String raw) {
        String v = normalizeEnumInput(raw, "MONTHLY");
        if ("MONTHLY".equals(v)) return ResetPeriod.Monthly;
        throw new OciException("预算重置周期仅支持 MONTHLY");
    }

    private static ProcessingPeriodType parseProcessingPeriodType(String raw) {
        String v = normalizeEnumInput(raw, "MONTH");
        return switch (v) {
            case "INVOICE" -> ProcessingPeriodType.Invoice;
            case "MONTH" -> ProcessingPeriodType.Month;
            case "SINGLE_USE" -> ProcessingPeriodType.SingleUse;
            default -> throw new OciException("预算处理周期仅支持 INVOICE、MONTH 或 SINGLE_USE");
        };
    }

    private static AlertType parseAlertType(String raw) {
        String v = normalizeEnumInput(raw, "ACTUAL");
        return switch (v) {
            case "ACTUAL" -> AlertType.Actual;
            case "FORECAST" -> AlertType.Forecast;
            default -> throw new OciException("告警类型仅支持 ACTUAL 或 FORECAST");
        };
    }

    private static ThresholdType parseThresholdType(String raw) {
        String v = normalizeEnumInput(raw, "PERCENTAGE");
        return switch (v) {
            case "PERCENTAGE" -> ThresholdType.Percentage;
            case "ABSOLUTE" -> ThresholdType.Absolute;
            default -> throw new OciException("阈值类型仅支持 PERCENTAGE 或 ABSOLUTE");
        };
    }

    private static String normalizeEnumInput(String raw, String fallback) {
        if (StrUtil.isBlank(raw)) return fallback;
        return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static List<String> parseTargets(Map<String, Object> params, TargetType targetType, OciUser user) {
        List<String> targets = new ArrayList<>();
        Object raw = params == null ? null : params.get("targets");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    targets.add(String.valueOf(item).trim());
                }
            }
        }
        String single = optionalString(params, "target");
        if (single == null) single = optionalString(params, "targetId");
        if (single != null) targets.add(single);
        if (targets.isEmpty() && targetType == TargetType.Compartment) {
            targets.add(user.getOciTenantId());
        }
        if (targets.isEmpty()) {
            throw new OciException("预算目标不能为空");
        }
        if (targets.size() > 1) {
            throw new OciException("OCI Budgets 当前仅支持一个预算目标");
        }
        return targets;
    }

    private static String requiredString(Map<String, Object> params, String key, String message) {
        String v = optionalString(params, key);
        if (v == null) throw new OciException(message);
        return v;
    }

    private static String optionalString(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key) || params.get(key) == null) return null;
        String v = String.valueOf(params.get(key)).trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean hasValue(Map<String, Object> params, String key) {
        return optionalString(params, key) != null || (params != null && params.get(key) instanceof Number);
    }

    private static BigDecimal requiredPositiveDecimal(Map<String, Object> params, String key, String message) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null || StrUtil.isBlank(String.valueOf(raw))) throw new OciException(message);
        try {
            BigDecimal d = new BigDecimal(String.valueOf(raw).trim());
            if (d.compareTo(BigDecimal.ZERO) <= 0) throw new OciException(message);
            return d;
        } catch (NumberFormatException e) {
            throw new OciException(message);
        }
    }

    private static Integer optionalInt(Map<String, Object> params, String key) {
        if (params == null || params.get(key) == null || StrUtil.isBlank(String.valueOf(params.get(key)))) return null;
        try {
            return Integer.parseInt(String.valueOf(params.get(key)).trim());
        } catch (NumberFormatException e) {
            throw new OciException(key + " 必须是整数");
        }
    }

    private static Date parseDate(Object raw) {
        if (raw == null || StrUtil.isBlank(String.valueOf(raw))) return null;
        String s = String.valueOf(raw).trim();
        try {
            return Date.from(Instant.parse(s));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Date.from(OffsetDateTime.parse(s).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Date.from(LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException e) {
            throw new OciException("日期格式需为 RFC3339 或 yyyy-MM-dd: " + s);
        }
    }

    private static String decimalString(BigDecimal d) {
        return d == null ? null : d.stripTrailingZeros().toPlainString();
    }

    private static String percentString(BigDecimal value, BigDecimal amount) {
        if (value == null || amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return null;
        return value.multiply(BigDecimal.valueOf(100))
                .divide(amount, 2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String dateString(Date d) {
        return d == null ? null : d.toInstant().toString();
    }

    private static String enumValue(Object e) {
        if (e == null) return null;
        try {
            Method m = e.getClass().getMethod("getValue");
            Object v = m.invoke(e);
            return v == null ? null : String.valueOf(v);
        } catch (Exception ignored) {
            return String.valueOf(e);
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String tag(OciUser user) {
        return "[" + user.getUsername() + "] ";
    }
}
