package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.ospgateway.AddressServiceClient;
import com.oracle.bmc.ospgateway.InvoiceServiceClient;
import com.oracle.bmc.ospgateway.SubscriptionServiceClient;
import com.oracle.bmc.ospgateway.model.Address;
import com.oracle.bmc.ospgateway.model.Invoice;
import com.oracle.bmc.ospgateway.model.InvoiceLineSummary;
import com.oracle.bmc.ospgateway.model.PayInvoiceDetails;
import com.oracle.bmc.ospgateway.model.PayInvoiceReceipt;
import com.oracle.bmc.ospgateway.model.Subscription;
import com.oracle.bmc.ospgateway.model.SubscriptionSummary;
import com.oracle.bmc.ospgateway.model.VerifyAddressDetails;
import com.oracle.bmc.ospgateway.model.VerifyAddressReceipt;
import com.oracle.bmc.ospgateway.requests.GetAddressRequest;
import com.oracle.bmc.ospgateway.requests.GetInvoiceRequest;
import com.oracle.bmc.ospgateway.requests.GetSubscriptionRequest;
import com.oracle.bmc.ospgateway.requests.ListInvoiceLinesRequest;
import com.oracle.bmc.ospgateway.requests.ListSubscriptionsRequest;
import com.oracle.bmc.ospgateway.requests.PayInvoiceRequest;
import com.oracle.bmc.ospgateway.requests.PaySubscriptionRequest;
import com.oracle.bmc.ospgateway.requests.UpdateSubscriptionRequest;
import com.oracle.bmc.ospgateway.requests.VerifyAddressRequest;
import com.oracle.bmc.ospgateway.model.PaySubscriptionDetails;
import com.oracle.bmc.ospgateway.model.UpdateSubscriptionDetails;
import com.oracle.bmc.retrier.RetryConfiguration;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * OSP/Billing 的可变操作与详情查询。
 *
 * <p>支付只创建 Oracle 托管的支付会话/授权，不在 OCIWorker 接触银行卡数据；
 * 地址验证使用 OSP AddressService，地址保存通过 SubscriptionService 更新账单地址。</p>
 */
@Slf4j
@Service
public class BillingService {

    private static final String ORACLE_PAYMENT_URL = "https://shop.oracle.com";

    private static final ClientConfiguration OSP_CLIENT_CONFIGURATION = ClientConfiguration.builder()
            .connectionTimeoutMillis(5_000)
            .readTimeoutMillis(20_000)
            .build();

    @Resource
    private OciUserMapper userMapper;

    public Map<String, Object> workspace(String id) {
        OciUser user = requireUser(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", Boolean.FALSE);
        out.put("subscription", null);
        out.put("address", null);
        out.put("reason", null);
        out.put("links", officialLinks(user));

        try (OciClientService client = openClient(user);
             SubscriptionServiceClient subscriptionClient = buildSubscriptionClient(client)) {
            String homeRegion = ospHomeRegion(client, user);
            String compartmentId = client.getCompartmentId();
            SubscriptionSummary summary = firstSubscription(subscriptionClient, homeRegion, compartmentId);
            Subscription subscription = null;
            if (summary != null && StrUtil.isNotBlank(summary.getId())) {
                subscription = getSubscription(subscriptionClient, homeRegion, compartmentId, summary.getId());
            }
            Object source = subscription != null ? subscription : summary;
            if (source != null) {
                Map<String, Object> subscriptionMap = mapSubscription(source);
                out.put("subscription", subscriptionMap);
                Address address = subscription != null ? subscription.getBillingAddress() : summary.getBillingAddress();
                if (address != null && StrUtil.isNotBlank(address.getAddressKey())) {
                    try (AddressServiceClient addressClient = buildAddressClient(client)) {
                        Address remote = addressClient.getAddress(GetAddressRequest.builder()
                                .ospHomeRegion(homeRegion)
                                .compartmentId(compartmentId)
                                .addressId(address.getAddressKey())
                                .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                                .build()).getAddress();
                        if (remote != null) address = remote;
                    } catch (Exception e) {
                        log.debug("读取 OSP 账单地址详情失败: {}", e.getMessage());
                    }
                }
                out.put("address", mapAddress(address));
            }
            out.put("available", Boolean.TRUE);
            return out;
        } catch (Exception e) {
            out.put("reason", errorMessage("读取账务订阅信息失败", e));
            return out;
        }
    }

    public Map<String, Object> invoiceDetails(String id, String invoiceId, int lineLimit) {
        OciUser user = requireUser(id);
        if (StrUtil.isBlank(invoiceId)) throw new OciException("invoiceId 不能为空");
        int safeLimit = Math.max(1, Math.min(200, lineLimit <= 0 ? 200 : lineLimit));
        try (OciClientService client = openClient(user);
             InvoiceServiceClient invoiceClient = buildInvoiceClient(client)) {
            String homeRegion = ospHomeRegion(client, user);
            String compartmentId = client.getCompartmentId();
            Invoice invoice = invoiceClient.getInvoice(GetInvoiceRequest.builder()
                    .ospHomeRegion(homeRegion)
                    .compartmentId(compartmentId)
                    .internalInvoiceId(invoiceId.trim())
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                    .build()).getInvoice();
            if (invoice == null) throw new OciException("发票不存在或无权访问");

            Map<String, Object> out = mapInvoice(invoice);
            var linesResponse = invoiceClient.listInvoiceLines(ListInvoiceLinesRequest.builder()
                    .ospHomeRegion(homeRegion)
                    .compartmentId(compartmentId)
                    .internalInvoiceId(invoiceId.trim())
                    .limit(safeLimit)
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                    .build());
            List<Map<String, Object>> lines = new ArrayList<>();
            if (linesResponse != null && linesResponse.getInvoiceLineCollection() != null
                    && linesResponse.getInvoiceLineCollection().getItems() != null) {
                for (InvoiceLineSummary line : linesResponse.getInvoiceLineCollection().getItems()) {
                    lines.add(mapInvoiceLine(line));
                }
            }
            out.put("lines", lines);
            return out;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(errorMessage("读取发票明细失败", e));
        }
    }

    public Map<String, Object> startInvoicePayment(
            String id, String invoiceId, String returnUrl, String email) {
        OciUser user = requireUser(id);
        if (StrUtil.isBlank(invoiceId)) throw new OciException("invoiceId 不能为空");
        try (OciClientService client = openClient(user);
             InvoiceServiceClient invoiceClient = buildInvoiceClient(client)) {
            String homeRegion = ospHomeRegion(client, user);
            String compartmentId = client.getCompartmentId();
            Invoice invoice = invoiceClient.getInvoice(GetInvoiceRequest.builder()
                    .ospHomeRegion(homeRegion)
                    .compartmentId(compartmentId)
                    .internalInvoiceId(invoiceId.trim())
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                    .build()).getInvoice();
            if (invoice == null) throw new OciException("发票不存在或无权访问");
            if (Boolean.FALSE.equals(invoice.getIsPayable())) {
                throw new OciException("该发票当前不可支付");
            }

            String payerEmail = firstValidEmail(email, invoice.getPreferredEmail(), user.getUsername());
            PayInvoiceDetails.Builder details = PayInvoiceDetails.builder()
                    .languageCode("zh-CN");
            if (isEmail(payerEmail)) details.email(payerEmail);
            if (isHttpUrl(returnUrl)) details.returnUrl(returnUrl.trim());
            PayInvoiceReceipt receipt = invoiceClient.payInvoice(PayInvoiceRequest.builder()
                    .ospHomeRegion(homeRegion)
                    .compartmentId(compartmentId)
                    .internalInvoiceId(invoiceId.trim())
                    .payInvoiceDetails(details.build())
                    .opcRetryToken("ociworker-billing-" + UUID.randomUUID())
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                    .build()).getPayInvoiceReceipt();
            if (receipt == null) throw new OciException("Oracle 未返回支付会话");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", "invoice");
            out.put("invoiceId", invoiceId.trim());
            out.put("url", receipt.getUrl());
            out.put("sessionCreated", Boolean.TRUE);
            out.put("officialUrl", officialLinks(user).get("billingAccount"));
            out.put("requiresOfficialPage", Boolean.TRUE);
            return out;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(errorMessage("创建发票支付会话失败", e));
        }
    }

    /**
     * 创建订阅支付会话。OSP 的订阅支付回执返回 Payment Service token 而不是 URL，
     * 因此同时返回 Oracle 官方账务地址，前端引导用户在官方页面完成支付。
     */
    public Map<String, Object> startSubscriptionPayment(String id, String subscriptionId, String email) {
        OciUser user = requireUser(id);
        try (OciClientService client = openClient(user);
             SubscriptionServiceClient subscriptionClient = buildSubscriptionClient(client)) {
            String homeRegion = ospHomeRegion(client, user);
            String compartmentId = client.getCompartmentId();
            String resolvedId = resolveSubscriptionId(subscriptionClient, homeRegion, compartmentId, subscriptionId);
            if (StrUtil.isBlank(resolvedId)) throw new OciException("没有可支付的 OSP 订阅");
            Subscription subscription = getSubscription(subscriptionClient, homeRegion, compartmentId, resolvedId);
            if (subscription == null) throw new OciException("订阅不存在或无权访问");
            subscription = withNonNullPaymentOptions(subscription.toBuilder());
            String payerEmail = firstValidEmail(email,
                    subscription.getBillingAddress() == null ? null : subscription.getBillingAddress().getEmailAddress(),
                    user.getUsername());
            PaySubscriptionDetails.Builder details = PaySubscriptionDetails.builder()
                    .subscription(subscription)
                    .languageCode("zh-CN");
            if (isEmail(payerEmail)) details.email(payerEmail);
            var response = subscriptionClient.paySubscription(PaySubscriptionRequest.builder()
                    .ospHomeRegion(homeRegion)
                    .compartmentId(compartmentId)
                    .subscriptionId(resolvedId)
                    .paySubscriptionDetails(details.build())
                    .opcRetryToken("ociworker-subscription-" + UUID.randomUUID())
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                    .build());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", "subscription");
            out.put("subscriptionId", resolvedId);
            out.put("officialUrl", officialLinks(user).get("billingAccount"));
            out.put("requiresOfficialPage", Boolean.TRUE);
            out.put("sessionCreated", response != null && response.getPaySubscriptionReceipt() != null);
            return out;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(errorMessage("创建订阅支付授权失败", e));
        }
    }

    public Map<String, Object> verifyAddress(String id, Map<String, Object> params) {
        OciUser user = requireUser(id);
        VerifyAddressDetails details = verifyAddressDetails(params);
        try (OciClientService client = openClient(user);
             AddressServiceClient addressClient = buildAddressClient(client)) {
            String homeRegion = ospHomeRegion(client, user);
            VerifyAddressReceipt receipt = addressClient.verifyAddress(VerifyAddressRequest.builder()
                    .ospHomeRegion(homeRegion)
                    .compartmentId(client.getCompartmentId())
                    .verifyAddressDetails(details)
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                    .build()).getVerifyAddressReceipt();
            if (receipt == null) throw new OciException("Oracle 未返回地址验证结果");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("verified", "VERIFIED".equalsIgnoreCase(enumValue(receipt.getVerificationCode())));
            out.put("quality", enumValue(receipt.getQuality()));
            out.put("verificationCode", enumValue(receipt.getVerificationCode()));
            out.put("address", mapAddress(receipt.getAddress()));
            return out;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(errorMessage("验证账单地址失败", e));
        }
    }

    public Map<String, Object> updateAddress(String id, Map<String, Object> params) {
        OciUser user = requireUser(id);
        try (OciClientService client = openClient(user);
             SubscriptionServiceClient subscriptionClient = buildSubscriptionClient(client)) {
            String homeRegion = ospHomeRegion(client, user);
            String compartmentId = client.getCompartmentId();
            String subscriptionId = resolveSubscriptionId(subscriptionClient, homeRegion, compartmentId,
                    text(params, "subscriptionId"));
            if (StrUtil.isBlank(subscriptionId)) throw new OciException("没有可更新的 OSP 订阅");
            var currentResponse = subscriptionClient.getSubscription(GetSubscriptionRequest.builder()
                    .ospHomeRegion(homeRegion)
                    .compartmentId(compartmentId)
                    .subscriptionId(subscriptionId)
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                    .build());
            Subscription current = currentResponse == null ? null : currentResponse.getSubscription();
            if (current == null) throw new OciException("订阅不存在或无权访问");
            Address address = addressFromParams(params, current.getBillingAddress());
            Subscription updated = withNonNullPaymentOptions(
                    current.toBuilder().billingAddress(address));
            UpdateSubscriptionDetails.Builder detailsBuilder = UpdateSubscriptionDetails.builder()
                    .subscription(updated);
            String email = firstValidEmail(text(params, "email"), text(params, "emailAddress"),
                    current.getBillingAddress() == null ? null : current.getBillingAddress().getEmailAddress(),
                    user.getUsername());
            if (isEmail(email)) detailsBuilder.email(email);
            UpdateSubscriptionDetails details = detailsBuilder.build();
            UpdateSubscriptionRequest.Builder request = UpdateSubscriptionRequest.builder()
                    .ospHomeRegion(homeRegion)
                    .compartmentId(compartmentId)
                    .subscriptionId(subscriptionId)
                    .updateSubscriptionDetails(details)
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION);
            String etag = firstNonBlank(text(params, "ifMatch"), currentResponse.getEtag());
            if (StrUtil.isNotBlank(etag)) request.ifMatch(etag);
            var response = subscriptionClient.updateSubscription(request.build());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("subscriptionId", subscriptionId);
            out.put("etag", response == null ? null : response.getEtag());
            out.put("subscription", response == null ? null : mapSubscription(response.getSubscription()));
            out.put("address", mapAddress(response == null || response.getSubscription() == null
                    ? address : response.getSubscription().getBillingAddress()));
            return out;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            log.warn("保存账单地址失败 tenant={}: {}", user.getUsername(), e.getMessage(), e);
            throw new OciException(errorMessage("保存账单地址失败", e));
        }
    }

    private OciUser requireUser(String id) {
        if (StrUtil.isBlank(id)) throw new OciException("ID不能为空");
        OciUser user = userMapper.selectById(id);
        if (user == null) throw new OciException("配置不存在");
        return user;
    }

    private static OciClientService openClient(OciUser user) {
        SysUserDTO dto = SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .compartmentId(user.getOciTenantId())
                        .build())
                .build();
        return new OciClientService(dto);
    }

    private static SubscriptionServiceClient buildSubscriptionClient(OciClientService client) {
        var builder = SubscriptionServiceClient.builder().configuration(OSP_CLIENT_CONFIGURATION);
        builder.additionalClientConfigurator(client.getOciClientConfigurator());
        return builder.build(client.getProvider());
    }

    private static InvoiceServiceClient buildInvoiceClient(OciClientService client) {
        var builder = InvoiceServiceClient.builder().configuration(OSP_CLIENT_CONFIGURATION);
        builder.additionalClientConfigurator(client.getOciClientConfigurator());
        return builder.build(client.getProvider());
    }

    private static AddressServiceClient buildAddressClient(OciClientService client) {
        var builder = AddressServiceClient.builder().configuration(OSP_CLIENT_CONFIGURATION);
        builder.additionalClientConfigurator(client.getOciClientConfigurator());
        return builder.build(client.getProvider());
    }

    private static String ospHomeRegion(OciClientService client, OciUser user) {
        return UsageCostService.resolveTenancyHomeRegionName(
                client.getIdentityClient(), user.getOciTenantId(), user.getOciRegion());
    }

    private static SubscriptionSummary firstSubscription(
            SubscriptionServiceClient client, String homeRegion, String compartmentId) {
        var response = client.listSubscriptions(ListSubscriptionsRequest.builder()
                .ospHomeRegion(homeRegion)
                .compartmentId(compartmentId)
                .limit(10)
                .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                .build());
        if (response == null || response.getSubscriptionCollection() == null
                || response.getSubscriptionCollection().getItems() == null) return null;
        return response.getSubscriptionCollection().getItems().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static Subscription getSubscription(
            SubscriptionServiceClient client, String homeRegion, String compartmentId, String id) {
        if (StrUtil.isBlank(id)) return null;
        var response = client.getSubscription(GetSubscriptionRequest.builder()
                .ospHomeRegion(homeRegion)
                .compartmentId(compartmentId)
                .subscriptionId(id)
                .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                .build());
        return response == null ? null : response.getSubscription();
    }

    private static String resolveSubscriptionId(
            SubscriptionServiceClient client, String homeRegion, String compartmentId, String requested) {
        if (StrUtil.isNotBlank(requested)) return requested.trim();
        SubscriptionSummary summary = firstSubscription(client, homeRegion, compartmentId);
        return summary == null ? null : summary.getId();
    }

    private static Map<String, Object> mapSubscription(Object subscription) {
        if (subscription == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        OspSubscriptionEnricher.enrich(subscription, out);
        out.putIfAbsent("id", invoke(subscription, "getId"));
        out.putIfAbsent("subscriptionPlanNumber", invoke(subscription, "getSubscriptionPlanNumber"));
        out.putIfAbsent("currencyCode", invoke(subscription, "getCurrencyCode"));
        out.put("billingAddress", mapAddress(asAddress(invoke(subscription, "getBillingAddress"))));
        Object options = invoke(subscription, "getPaymentOptions");
        String paymentMethod = firstPaymentMethod(options);
        if (StrUtil.isNotBlank(paymentMethod)) {
            out.putIfAbsent("paymentMethod", paymentMethod);
            out.putIfAbsent("paymentMethodLabel", OspSubscriptionEnricher.labelPaymentMethod(paymentMethod));
        }
        return out;
    }

    private static Map<String, Object> mapInvoice(Invoice invoice) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoiceId", invoice.getInternalInvoiceId());
        out.put("invoiceNo", invoice.getInvoiceNumber());
        out.put("refNo", invoice.getInvoiceRefNumber());
        out.put("status", enumValue(invoice.getInvoiceStatus()));
        out.put("type", enumValue(invoice.getInvoiceType()));
        out.put("invoiceDate", formatDate(invoice.getTimeInvoice()));
        out.put("dueDate", formatDate(invoice.getTimeInvoiceDue()));
        out.put("totalAmount", invoice.getInvoiceAmount());
        out.put("amountDue", invoice.getInvoiceAmountDue());
        out.put("currencyCode", invoice.getCurrency() == null ? null : invoice.getCurrency().getCurrencyCode());
        out.put("payable", invoice.getIsPayable());
        out.put("creditCardPayable", invoice.getIsCreditCardPayable());
        out.put("preferredEmail", invoice.getPreferredEmail());
        out.put("paymentTerms", invoice.getPaymentTerms());
        out.put("billToAddress", mapBillToAddress(invoice.getBillToAddress()));
        return out;
    }

    private static Map<String, Object> mapInvoiceLine(InvoiceLineSummary line) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", line.getProduct());
        out.put("orderNo", line.getOrderNo());
        out.put("partNumber", line.getPartNumber());
        out.put("timeStart", formatDate(line.getTimeStart()));
        out.put("timeEnd", formatDate(line.getTimeEnd()));
        out.put("quantity", line.getQuantity());
        out.put("netUnitPrice", line.getNetUnitPrice());
        out.put("totalPrice", line.getTotalPrice());
        out.put("currencyCode", line.getCurrency() == null ? null : line.getCurrency().getCurrencyCode());
        return out;
    }

    private static Map<String, Object> mapAddress(Address address) {
        if (address == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("addressKey", address.getAddressKey());
        out.put("line1", address.getLine1());
        out.put("line2", address.getLine2());
        out.put("line3", address.getLine3());
        out.put("line4", address.getLine4());
        out.put("streetName", address.getStreetName());
        out.put("streetNumber", address.getStreetNumber());
        out.put("city", address.getCity());
        out.put("county", address.getCounty());
        out.put("country", address.getCountry());
        out.put("province", address.getProvince());
        out.put("postalCode", address.getPostalCode());
        out.put("state", address.getState());
        out.put("emailAddress", address.getEmailAddress());
        out.put("companyName", address.getCompanyName());
        out.put("firstName", address.getFirstName());
        out.put("middleName", address.getMiddleName());
        out.put("lastName", address.getLastName());
        out.put("phoneCountryCode", address.getPhoneCountryCode());
        out.put("phoneNumber", address.getPhoneNumber());
        out.put("jobTitle", address.getJobTitle());
        out.put("departmentName", address.getDepartmentName());
        out.put("internalNumber", address.getInternalNumber());
        out.put("contributorClass", address.getContributorClass());
        out.put("stateInscription", address.getStateInscription());
        out.put("municipalInscription", address.getMunicipalInscription());
        return out;
    }

    private static Map<String, Object> mapBillToAddress(Object address) {
        if (address == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String getter : List.of("getContactName", "getCompanyName", "getAddressLine1", "getAddressLine2",
                "getAddressLine3", "getAddressLine4", "getStreetName", "getStreetNumber", "getCity",
                "getCounty", "getState", "getPostalCode", "getProvince")) {
            String key = getter.substring(3);
            out.put(decapitalize(key), invoke(address, getter));
        }
        Object country = invoke(address, "getCountry");
        String countryValue = asString(invoke(country, "getCountryCode"));
        if (StrUtil.isBlank(countryValue)) countryValue = asString(invoke(country, "getName"));
        if (StrUtil.isBlank(countryValue)) countryValue = asString(country);
        out.put("country", countryValue);
        return out;
    }

    private static VerifyAddressDetails verifyAddressDetails(Map<String, Object> params) {
        return VerifyAddressDetails.builder()
                .addressKey(text(params, "addressKey"))
                .line1(required(params, "line1", "地址行 1"))
                .line2(text(params, "line2"))
                .line3(text(params, "line3"))
                .line4(text(params, "line4"))
                .streetName(text(params, "streetName"))
                .streetNumber(text(params, "streetNumber"))
                .city(required(params, "city", "城市"))
                .county(text(params, "county"))
                .country(required(params, "country", "国家"))
                .province(text(params, "province"))
                .postalCode(required(params, "postalCode", "邮编"))
                .state(text(params, "state"))
                .emailAddress(text(params, "emailAddress"))
                .companyName(text(params, "companyName"))
                .firstName(text(params, "firstName"))
                .middleName(text(params, "middleName"))
                .lastName(text(params, "lastName"))
                .phoneCountryCode(text(params, "phoneCountryCode"))
                .phoneNumber(text(params, "phoneNumber"))
                .jobTitle(text(params, "jobTitle"))
                .departmentName(text(params, "departmentName"))
                .internalNumber(text(params, "internalNumber"))
                .contributorClass(text(params, "contributorClass"))
                .stateInscription(text(params, "stateInscription"))
                .municipalInscription(text(params, "municipalInscription"))
                .build();
    }

    private static Address addressFromParams(Map<String, Object> params, Address current) {
        Address.Builder builder = current == null ? Address.builder() : current.toBuilder();
        setIfProvided(builder::addressKey, text(params, "addressKey"));
        setIfProvided(builder::line1, text(params, "line1"));
        setIfProvided(builder::line2, text(params, "line2"));
        setIfProvided(builder::line3, text(params, "line3"));
        setIfProvided(builder::line4, text(params, "line4"));
        setIfProvided(builder::streetName, text(params, "streetName"));
        setIfProvided(builder::streetNumber, text(params, "streetNumber"));
        setIfProvided(builder::city, text(params, "city"));
        setIfProvided(builder::county, text(params, "county"));
        setIfProvided(builder::country, text(params, "country"));
        setIfProvided(builder::province, text(params, "province"));
        setIfProvided(builder::postalCode, text(params, "postalCode"));
        setIfProvided(builder::state, text(params, "state"));
        setIfProvided(builder::emailAddress, text(params, "emailAddress"));
        setIfProvided(builder::companyName, text(params, "companyName"));
        setIfProvided(builder::firstName, text(params, "firstName"));
        setIfProvided(builder::middleName, text(params, "middleName"));
        setIfProvided(builder::lastName, text(params, "lastName"));
        setIfProvided(builder::phoneCountryCode, text(params, "phoneCountryCode"));
        setIfProvided(builder::phoneNumber, text(params, "phoneNumber"));
        setIfProvided(builder::jobTitle, text(params, "jobTitle"));
        setIfProvided(builder::departmentName, text(params, "departmentName"));
        setIfProvided(builder::internalNumber, text(params, "internalNumber"));
        setIfProvided(builder::contributorClass, text(params, "contributorClass"));
        setIfProvided(builder::stateInscription, text(params, "stateInscription"));
        setIfProvided(builder::municipalInscription, text(params, "municipalInscription"));
        return builder.build();
    }

    /**
     * OSP 模型是 ExplicitlySetBmcModel：setter 一旦被调用，即使传 null 也会被序列化成
     * 显式 "field": null 提交给 Oracle，与控制台请求形态不同且可能触发 400。
     * 因此只对确实传入的非空字段调用 setter，其余保持 GET 返回的原样。
     */
    /**
     * OSP UpdateSubscription/PaySubscription 会校验 "paymentOption is null!"：
     * 无保存支付方式的账户 GET 到的 paymentOptions 为 null，回传前须显式置为空数组。
     */
    private static Subscription withNonNullPaymentOptions(Subscription.Builder builder) {
        Subscription built = builder.build();
        if (built.getPaymentOptions() == null) {
            return builder.paymentOptions(java.util.Collections.emptyList()).build();
        }
        return built;
    }

    private static void setIfProvided(java.util.function.Consumer<String> setter, String value) {
        if (StrUtil.isNotBlank(value)) setter.accept(value);
    }

    private static Map<String, Object> officialLinks(OciUser user) {
        String region = user.getOciRegion() == null ? "" : user.getOciRegion();
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("billingAccount", ORACLE_PAYMENT_URL);
        links.put("invoices", "https://cloud.oracle.com/billing/invoices?region=" + region);
        links.put("payments", "https://cloud.oracle.com/billing/payments?region=" + region);
        return links;
    }

    private static String firstPaymentMethod(Object options) {
        if (!(options instanceof List<?> list)) return null;
        for (Object option : list) {
            String method = asString(invoke(option, "getPaymentMethod"));
            if (StrUtil.isBlank(method) && option != null) {
                String simple = option.getClass().getSimpleName();
                if (simple.contains("FreeTrial")) method = "FREE_TRIAL";
                else if (simple.contains("CreditCard")) method = "CREDIT_CARD";
                else if (simple.contains("Paypal")) method = "PAYPAL";
            }
            if (StrUtil.isNotBlank(method)) return method;
        }
        return null;
    }

    private static String required(Map<String, Object> params, String key, String label) {
        String value = text(params, key);
        if (StrUtil.isBlank(value)) throw new OciException(label + "不能为空");
        return value;
    }

    private static String text(Map<String, Object> params, String key) {
        if (params == null || params.get(key) == null) return null;
        String value = String.valueOf(params.get(key)).trim();
        return value.isEmpty() ? null : value;
    }

    private static String firstNonBlank(String first, String second) {
        return StrUtil.isNotBlank(first) ? first : (StrUtil.isNotBlank(second) ? second : null);
    }

    private static String firstValidEmail(String... candidates) {
        if (candidates == null) return null;
        for (String candidate : candidates) {
            if (isEmail(candidate)) return candidate.trim();
        }
        return null;
    }

    private static boolean isEmail(String value) {
        if (StrUtil.isBlank(value)) return false;
        String candidate = value.trim();
        return candidate.length() <= 254
                && candidate.indexOf('@') > 0
                && candidate.indexOf('@') < candidate.length() - 1
                && !candidate.contains(" ");
    }

    private static String formatDate(Date date) {
        return date == null ? null : date.toInstant().toString();
    }

    private static String enumValue(Object value) {
        if (value == null) return null;
        Object raw = invoke(value, "getValue");
        return raw == null ? String.valueOf(value) : String.valueOf(raw);
    }

    private static Object invoke(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Address asAddress(Object value) {
        return value instanceof Address address ? address : null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String decapitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean isHttpUrl(String value) {
        if (StrUtil.isBlank(value)) return false;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static String errorMessage(String prefix, Exception error) {
        if (error instanceof BmcException bmc) {
            // OSP 的 400 常是具体字段校验失败，必须透出 Oracle 原始 detail 才能定位。
            String translated = OciBmcErrorTranslator.translateWithServiceDetail(bmc);
            if (StrUtil.isNotBlank(translated)) {
                return prefix + "：" + translated
                        + " (opc-request-id: " + StrUtil.blankToDefault(bmc.getOpcRequestId(), "unavailable") + ")";
            }
        }
        return prefix + "：" + (error == null || StrUtil.isBlank(error.getMessage())
                ? "未知错误" : error.getMessage());
    }
}
