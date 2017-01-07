/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */

package org.codice.ddf.admin.security.ldap;

import static org.codice.ddf.admin.api.handler.ConfigurationMessage.MessageType.FAILURE;
import static org.codice.ddf.admin.api.handler.ConfigurationMessage.MessageType.NO_TEST_FOUND;
import static org.codice.ddf.admin.api.handler.ConfigurationMessage.MessageType.REQUIRED_FIELDS;
import static org.codice.ddf.admin.api.handler.ConfigurationMessage.buildMessage;
import static org.codice.ddf.admin.security.ldap.LdapConfiguration.CREDENTIAL_STORE;
import static org.codice.ddf.admin.security.ldap.LdapConfiguration.LDAPS;
import static org.codice.ddf.admin.security.ldap.LdapConfiguration.LDAP_USE_CASES;
import static org.codice.ddf.admin.security.ldap.LdapConfiguration.LOGIN;
import static org.codice.ddf.admin.security.ldap.LdapConfiguration.LOGIN_AND_CREDENTIAL_STORE;
import static org.codice.ddf.admin.security.ldap.LdapConfiguration.TLS;
import static org.codice.ddf.admin.security.ldap.LdapConfigurationHandler.LdapTestResultType.CANNOT_BIND;
import static org.codice.ddf.admin.security.ldap.LdapConfigurationHandler.LdapTestResultType.CANNOT_CONFIGURE;
import static org.codice.ddf.admin.security.ldap.LdapConfigurationHandler.LdapTestResultType.CANNOT_CONNECT;
import static org.codice.ddf.admin.security.ldap.LdapConfigurationHandler.LdapTestResultType.SUCCESSFUL_BIND;
import static org.codice.ddf.admin.security.ldap.LdapConfigurationHandler.LdapTestResultType.SUCCESSFUL_CONNECTION;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;

import org.apache.cxf.common.util.StringUtils;
import org.codice.ddf.admin.api.handler.ConfigurationHandler;
import org.codice.ddf.admin.api.handler.ConfigurationMessage;
import org.codice.ddf.admin.api.handler.method.TestMethod;
import org.codice.ddf.admin.api.handler.report.CapabilitiesReport;
import org.codice.ddf.admin.api.handler.report.ProbeReport;
import org.codice.ddf.admin.api.handler.report.TestReport;
import org.codice.ddf.admin.api.persist.ConfigReport;
import org.codice.ddf.admin.api.persist.Configurator;
import org.codice.ddf.admin.security.ldap.test.AttributeMappingTestMethod;
import org.codice.ddf.admin.security.ldap.test.BindUserTestMethod;
import org.codice.ddf.admin.security.ldap.test.ConnectTestMethod;
import org.codice.ddf.admin.security.ldap.test.DirectoryStructTestMethod;
import org.forgerock.opendj.ldap.Attribute;
import org.forgerock.opendj.ldap.Connection;
import org.forgerock.opendj.ldap.LDAPConnectionFactory;
import org.forgerock.opendj.ldap.LDAPOptions;
import org.forgerock.opendj.ldap.LdapException;
import org.forgerock.opendj.ldap.SearchResultReferenceIOException;
import org.forgerock.opendj.ldap.SearchScope;
import org.forgerock.opendj.ldap.requests.BindRequest;
import org.forgerock.opendj.ldap.requests.DigestMD5SASLBindRequest;
import org.forgerock.opendj.ldap.requests.GSSAPISASLBindRequest;
import org.forgerock.opendj.ldap.requests.Requests;
import org.forgerock.opendj.ldap.responses.SearchResultEntry;
import org.forgerock.opendj.ldif.ConnectionEntryReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LdapConfigurationHandler implements ConfigurationHandler<LdapConfiguration> {
    private static final Logger LOGGER = LoggerFactory.getLogger(LdapConfigurationHandler.class);

    List<TestMethod> testMethods = Arrays.asList(new ConnectTestMethod(), new BindUserTestMethod(), new DirectoryStructTestMethod(), new AttributeMappingTestMethod());

    public static final String LDAP_CONFIGURATION_HANDLER_ID = "ldap";

    // Probe Ids
    public static final String LDAP_QUERY_PROBE_ID = "ldapQuery";

    public static final String LDAP_QUERY_RESULTS_ID = "ldapQueryResults";

    public static final String DISCOVER_LDAP_DIR_STRUCT_ID = "directoryStructure";

    public static final String BIND_USER_EXAMPLE = "bindUserExample";

    public static final String ATTRIBUTE_MAP_ID = "subjectAttributeMap";

    public static final String SUBJECT_CLAIMS_ID = "subjectClaims";

    public static final String LDAP_USER_ATTRIBUTES = "ldapUserAttributes";

    @Override
    public String getConfigurationHandlerId() {
        return LDAP_CONFIGURATION_HANDLER_ID;
    }

    @Override
    public Class<LdapConfiguration> getConfigClass() {
        return LdapConfiguration.class;
    }

    @Override
    public List<LdapConfiguration> getConfigurations() {
        return new Configurator().getManagedServiceConfigs("Ldap_Login_Config")
                .values()
                .stream()
                .map(serviceProps -> new LdapConfiguration(serviceProps))
                .collect(Collectors.toList());
    }

    @Override
    public CapabilitiesReport getCapabilities() {
        return new CapabilitiesReport(getConfigurationHandlerId(), getConfigurationHandlerId(), testMethods);
    }

    @Override
    public ProbeReport probe(String probeId, LdapConfiguration configuration) {
        switch (probeId) {
        case DISCOVER_LDAP_DIR_STRUCT_ID:
            return getDefaultDirectoryStructure(configuration);
        case BIND_USER_EXAMPLE:
            switch (configuration.ldapType()) {
            case "activeDirectory":
                return new ProbeReport(new ArrayList<>()).addProbeResult("bindUserDn",
                        "user@domain");
            default:
                return new ProbeReport(new ArrayList<>()).addProbeResult("bindUserDn", "cn=admin");
            }
            // TODO RAP 07 Dec 16:

        case LDAP_QUERY_PROBE_ID:
            Map<String, Object> connectionRequiredFields = new HashMap<>();
            connectionRequiredFields.put("hostName", configuration.hostName());
            connectionRequiredFields.put("port", configuration.port());
            connectionRequiredFields.put("encryptionMethod", configuration.encryptionMethod());
            connectionRequiredFields.put("bindUserDn", configuration.bindUserDn());
            connectionRequiredFields.put("bindUserPassword", configuration.bindUserPassword());
            connectionRequiredFields.put("query", configuration.query());
            connectionRequiredFields.put("queryBase", configuration.queryBase());

            TestReport nullFields = cannotBeNullFields(connectionRequiredFields);
            if (nullFields.containsUnsuccessfulMessages()) {
                return new ProbeReport(nullFields.getMessages());
            }
            // TODO: 11/14/16 Do checks on the connection
            LdapTestResult<Connection> connectionResult = bindUserToLdapConnection(configuration);
            List<SearchResultEntry> searchResults = getLdapQueryResults(connectionResult.value(),
                    configuration.query(),
                    configuration.queryBase());
            List<Map<String, String>> convertedSearchResults = new ArrayList<>();

            for (SearchResultEntry entry : searchResults) {
                Map<String, String> entryMap = new HashMap<>();
                for (Attribute attri : entry.getAllAttributes()) {
                    entryMap.put("name",
                            entry.getName()
                                    .toString());
                    entryMap.put(attri.getAttributeDescriptionAsString(),
                            attri.firstValueAsString());
                }
                convertedSearchResults.add(entryMap);
            }

            return new ProbeReport(new ArrayList<>()).addProbeResult(LDAP_QUERY_RESULTS_ID,
                    convertedSearchResults);

        case ATTRIBUTE_MAP_ID:
            // TODO: tbatie - 12/7/16 - Need to also return a default map is embedded ldap and set
            Object subjectClaims = new Configurator().getConfig(
                    "ddf.security.sts.client.configuration")
                    .get("claims");

            // TODO: tbatie - 12/6/16 - Clean up this naming conventions
            LdapTestResult<Connection> connection = bindUserToLdapConnection(configuration);
            Set<String> ldapEntryAttributes = null;
            try {
                ServerGuesser serverGuesser = ServerGuesser.buildGuesser(configuration.ldapType(),
                        connection.value());
                ldapEntryAttributes =
                        serverGuesser.getClaimAttributeOptions(configuration.baseGroupDn(),
                                configuration.membershipAttribute());
            } catch (SearchResultReferenceIOException | LdapException e) {
                LOGGER.warn("Error retrieving attributes from LDAP server; this may indicate a "
                                + "configuration issue with baseGroupDN {} or membershipAttribute {}",
                        configuration.baseGroupDn(),
                        configuration.membershipAttribute());
            }

            return new ProbeReport(new ArrayList<>()).addProbeResult(SUBJECT_CLAIMS_ID,
                    subjectClaims)
                    .addProbeResult(LDAP_USER_ATTRIBUTES, ldapEntryAttributes);
        }

        return new ProbeReport(Arrays.asList(buildMessage(FAILURE, "UNKNOWN PROBE ID")));
    }

    @Override
    public TestReport test(String testId, LdapConfiguration ldapConfiguration) {
        Optional<TestMethod> testMethod = testMethods.stream()
                .filter(method -> method.id().equals(testId))
                .findFirst();

        return testMethod.isPresent() ?
                testMethod.get().test(ldapConfiguration) :
                new TestReport(new ConfigurationMessage(NO_TEST_FOUND));
    }

    @Override
    public TestReport persist(LdapConfiguration config, String persistId) {
        Configurator configurator = new Configurator();
        ConfigReport report;

        switch (persistId) {
        case "create":
            if (!LDAP_USE_CASES.contains(config.ldapUseCase())) {
                return new TestReport(buildMessage(FAILURE,
                        "No ldap use case specified"));
            }

            if (config.ldapUseCase()
                    .equals(LOGIN) || config.ldapUseCase()
                    .equals(LOGIN_AND_CREDENTIAL_STORE)) {
                // TODO: tbatie - 12/8/16 - Perform validation and add a config to map option like the sources config
                Map<String, Object> ldapStsConfig = new HashMap<>();

                String ldapUrl = getLdapUrl(config);
                boolean startTls = isStartTls(config);

                ldapStsConfig.put("ldapBindUserDn", config.bindUserDn());
                ldapStsConfig.put("ldapBindUserPass", config.bindUserPassword());
                ldapStsConfig.put("bindMethod", config.bindUserMethod());
                ldapStsConfig.put("kdcAddress", config.bindKdcAddress());
                ldapStsConfig.put("realm", config.bindRealm());

                ldapStsConfig.put("userNameAttribute", config.userNameAttribute());
                ldapStsConfig.put("userBaseDn", config.baseUserDn());
                ldapStsConfig.put("groupBaseDn", config.baseGroupDn());

                ldapStsConfig.put("ldapUrl", ldapUrl + config.hostName() + ":" + config.port());
                ldapStsConfig.put("startTls", Boolean.toString(startTls));
                configurator.startFeature("security-sts-ldaplogin");
                configurator.createManagedService("Ldap_Login_Config", ldapStsConfig);
            }

            if (config.ldapUseCase()
                    .equals(CREDENTIAL_STORE) || config.ldapUseCase()
                    .equals(LOGIN_AND_CREDENTIAL_STORE)) {
                Path newAttributeMappingPath = Paths.get(System.getProperty("ddf.home"),
                        "etc",
                        "ws-security",
                        "ldapAttributeMap-" + UUID.randomUUID()
                                .toString() + ".props");
                configurator.createPropertyFile(newAttributeMappingPath,
                        config.attributeMappings());
                String ldapUrl = getLdapUrl(config);
                boolean startTls = isStartTls(config);

                Map<String, Object> ldapClaimsHandlerConfig = new HashMap<>();
                ldapClaimsHandlerConfig.put("url",
                        ldapUrl + config.hostName() + ":" + config.port());
                ldapClaimsHandlerConfig.put("startTls", startTls);
                ldapClaimsHandlerConfig.put("ldapBindUserDn", config.bindUserDn());
                ldapClaimsHandlerConfig.put("password", config.bindUserPassword());
                ldapClaimsHandlerConfig.put("membershipUserAttribute", config.userNameAttribute());
                ldapClaimsHandlerConfig.put("loginUserAttribute", config.userNameAttribute());
                ldapClaimsHandlerConfig.put("userBaseDn", config.baseUserDn());
                ldapClaimsHandlerConfig.put("objectClass", config.groupObjectClass());
                ldapClaimsHandlerConfig.put("memberNameAttribute", config.membershipAttribute());
                ldapClaimsHandlerConfig.put("groupBaseDn", config.baseGroupDn());
                ldapClaimsHandlerConfig.put("bindMethod", config.bindUserMethod());
                ldapClaimsHandlerConfig.put("propertyFileLocation",
                        newAttributeMappingPath.toString());

                configurator.startFeature("security-sts-ldapclaimshandler");
                configurator.createManagedService("Claims_Handler_Manager",
                        ldapClaimsHandlerConfig);
            }

            report = configurator.commit();
            if (!report.getFailedResults()
                    .isEmpty()) {
                return new TestReport(buildMessage(FAILURE,
                        "Unable to persist changes"));
            } else {
                return new TestReport(buildMessage(ConfigurationMessage.MessageType.SUCCESS,
                        "Successfully saved LDAP settings"));
            }
        case "delete":
            configurator.deleteManagedService(config.servicePid());
            report = configurator.commit();
            if (!report.getFailedResults()
                    .isEmpty()) {
                return new TestReport(buildMessage(FAILURE,
                        "Unable to delete LDAP Configuration"));
            } else {
                return new TestReport(buildMessage(ConfigurationMessage.MessageType.SUCCESS,
                        "Successfully deleted LDAP Configuration"));
            }
        default:
            return new TestReport(buildMessage(FAILURE, "Uknown persist id: " + persistId));
        }
    }


    public LdapTestResult<Connection> getLdapConnection(LdapConfiguration ldapConfiguration) {

        LDAPOptions ldapOptions = new LDAPOptions();

        try {
            if (ldapConfiguration.encryptionMethod()
                    .equalsIgnoreCase(LDAPS)) {
                ldapOptions.setSSLContext(SSLContext.getDefault());
            } else if (ldapConfiguration.encryptionMethod()
                    .equalsIgnoreCase(TLS)) {
                ldapOptions.setUseStartTLS(true);
            }

            ldapOptions.addEnabledCipherSuite(System.getProperty("https.cipherSuites")
                    .split(","));
            ldapOptions.addEnabledProtocol(System.getProperty("https.protocols")
                    .split(","));

            //sets the classloader so it can find the grizzly protocol handler class
            ldapOptions.setProviderClassLoader(LdapConfigurationHandler.class.getClassLoader());

        } catch (Exception e) {
            return new LdapTestResult<>(CANNOT_CONFIGURE);
        }

        Connection ldapConnection;

        try {
            ldapConnection = new LDAPConnectionFactory(ldapConfiguration.hostName(),
                    ldapConfiguration.port(),
                    ldapOptions).getConnection();
        } catch (Exception e) {
            return new LdapTestResult<>(CANNOT_CONNECT);
        }

        return new LdapTestResult<>(SUCCESSFUL_CONNECTION, ldapConnection);
    }

    public LdapTestResult<Connection> bindUserToLdapConnection(
            LdapConfiguration ldapConfiguration) {

        LdapTestResult<Connection> ldapConnectionResult = getLdapConnection(ldapConfiguration);
        if (ldapConnectionResult.type() != SUCCESSFUL_CONNECTION) {
            return ldapConnectionResult;
        }

        Connection connection = ldapConnectionResult.value();

        try {
            BindRequest bindRequest = selectBindMethod(ldapConfiguration.bindUserMethod(),
                    ldapConfiguration.bindUserDn(),
                    ldapConfiguration.bindUserPassword(),
                    ldapConfiguration.bindRealm(),
                    ldapConfiguration.bindKdcAddress());
            connection.bind(bindRequest);
        } catch (Exception e) {
            return new LdapTestResult<>(CANNOT_BIND);
        }

        return new LdapTestResult<>(SUCCESSFUL_BIND, connection);
    }

    public List<SearchResultEntry> getLdapQueryResults(Connection ldapConnection, String ldapQuery,
            String ldapSearchBaseDN) {

        final ConnectionEntryReader reader = ldapConnection.search(ldapSearchBaseDN,
                SearchScope.WHOLE_SUBTREE,
                ldapQuery);

        List<SearchResultEntry> entries = new ArrayList<>();

        try {
            while (reader.hasNext()) {
                if (!reader.isReference()) {
                    SearchResultEntry resultEntry = reader.readEntry();
                    entries.add(resultEntry);
                } else {
                    reader.readReference();
                }
            }
        } catch (IOException e) {
            reader.close();
        }

        reader.close();
        return entries;
    }

    public TestReport cannotBeNullFields(Map<String, Object> fieldsToCheck) {
        List<ConfigurationMessage> missingFields = new ArrayList<>();

        fieldsToCheck.entrySet()
                .stream()
                .filter(field -> field.getValue() == null && (field.getValue() instanceof String
                        && StringUtils.isEmpty((String) field.getValue())))
                .forEach(field -> missingFields.add(buildMessage(REQUIRED_FIELDS,
                        "Field cannot be empty").configId(field.getKey())));

        return new TestReport(missingFields);
    }

    private TestReport testConditionalBindFields(LdapConfiguration ldapConfiguration) {
        List<ConfigurationMessage> missingFields = new ArrayList<>();

        // TODO RAP 08 Dec 16: So many magic strings
        // TODO RAP 08 Dec 16: StringUtils
        String bindMethod = ldapConfiguration.bindUserMethod();
        if (bindMethod.equals("GSSAPI SASL")) {
            if (ldapConfiguration.bindKdcAddress() == null || ldapConfiguration.bindKdcAddress()
                    .equals("")) {
                missingFields.add(buildMessage(REQUIRED_FIELDS,
                        "Field cannot be empty for GSSAPI SASL bind type").configId("bindKdcAddress"));
            }
            if (ldapConfiguration.bindRealm() == null || ldapConfiguration.bindRealm()
                    .equals("")) {
                missingFields.add(buildMessage(REQUIRED_FIELDS,
                        "Field cannot be empty for GSSAPI SASL bind type").configId("bindRealm"));
            }
        }

        return new TestReport(missingFields);
    }

    ProbeReport getDefaultDirectoryStructure(LdapConfiguration configuration) {
        ProbeReport probeReport = new ProbeReport(new ArrayList<>());

        String ldapType = configuration.ldapType();
        ServerGuesser guesser = ServerGuesser.buildGuesser(ldapType,
                bindUserToLdapConnection(configuration).value);

        if (guesser != null) {
            probeReport.addProbeResult("baseUserDn", guesser.getUserBaseChoices());
            probeReport.addProbeResult("baseGroupDn", guesser.getGroupBaseChoices());
            probeReport.addProbeResult("userNameAttribute", guesser.getUserNameAttribute());
            probeReport.addProbeResult("groupObjectClass", guesser.getGroupObjectClass());
            probeReport.addProbeResult("membershipAttribute", guesser.getMembershipAttribute());

            // TODO RAP 13 Dec 16: Better query, perhaps driven by guessers?
            probeReport.addProbeResult("query", Collections.singletonList("objectClass=*"));
            probeReport.addProbeResult("queryBase", guesser.getBaseContexts());
        }

        return probeReport;
    }

    // TODO RAP 08 Dec 16: Refactor to common location...this functionality is in BindMethodChooser
    // and SslLdapLoginModule as well
    private static BindRequest selectBindMethod(String bindMethod, String bindUserDN,
            String bindUserCredentials, String realm, String kdcAddress) {
        BindRequest request;
        switch (bindMethod) {
        case "Simple":
            request = Requests.newSimpleBindRequest(bindUserDN, bindUserCredentials.toCharArray());
            break;
        case "SASL":
            request = Requests.newPlainSASLBindRequest(bindUserDN,
                    bindUserCredentials.toCharArray());
            break;
        case "GSSAPI SASL":
            request = Requests.newGSSAPISASLBindRequest(bindUserDN,
                    bindUserCredentials.toCharArray());
            ((GSSAPISASLBindRequest) request).setRealm(realm);
            ((GSSAPISASLBindRequest) request).setKDCAddress(kdcAddress);
            break;
        case "Digest MD5 SASL":
            request = Requests.newDigestMD5SASLBindRequest(bindUserDN,
                    bindUserCredentials.toCharArray());
            ((DigestMD5SASLBindRequest) request).setCipher(DigestMD5SASLBindRequest.CIPHER_HIGH);
            ((DigestMD5SASLBindRequest) request).getQOPs()
                    .clear();
            ((DigestMD5SASLBindRequest) request).getQOPs()
                    .add(DigestMD5SASLBindRequest.QOP_AUTH_CONF);
            ((DigestMD5SASLBindRequest) request).getQOPs()
                    .add(DigestMD5SASLBindRequest.QOP_AUTH_INT);
            ((DigestMD5SASLBindRequest) request).getQOPs()
                    .add(DigestMD5SASLBindRequest.QOP_AUTH);
            if (realm != null && !realm.equals("")) {
                //            if (StringUtils.isNotEmpty(realm)) {
                ((DigestMD5SASLBindRequest) request).setRealm(realm);
            }
            break;
        default:
            request = Requests.newSimpleBindRequest(bindUserDN, bindUserCredentials.toCharArray());
            break;
        }

        return request;
    }

    private boolean isStartTls(LdapConfiguration config) {
        return config.encryptionMethod()
                .equalsIgnoreCase(TLS);
    }

    private String getLdapUrl(LdapConfiguration config) {
        return config.encryptionMethod()
                .equalsIgnoreCase(LDAPS) ? "ldaps://" : "ldap://";
    }

    public enum LdapTestResultType {
        SUCCESSFUL_CONNECTION, CANNOT_CONNECT, CANNOT_CONFIGURE, CANNOT_BIND, SUCCESSFUL_BIND
    }

    public static class LdapTestResult<T> {

        private LdapTestResultType type;

        private T value;

        public LdapTestResult(LdapTestResultType ldapTestResultType) {
            this.type = ldapTestResultType;
        }

        public LdapTestResult(LdapTestResultType ldapTestResultType, T value) {
            this.type = ldapTestResultType;
            this.value = value;
        }

        public T value() {
            return value;
        }

        public LdapTestResultType type() {
            return type;
        }
    }
}
