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
package org.codice.ddf.admin.sources.wfs.persist

import ddf.catalog.source.FederatedSource
import org.codice.ddf.admin.common.report.message.DefaultMessages
import org.codice.ddf.admin.common.services.ServiceCommons
import org.codice.ddf.admin.configurator.Configurator
import org.codice.ddf.admin.configurator.ConfiguratorFactory
import org.codice.ddf.admin.sources.SourceMessages
import org.codice.ddf.admin.sources.fields.WfsVersion
import org.codice.ddf.admin.sources.fields.type.WfsSourceConfigurationField
import org.codice.ddf.admin.sources.test.SourceCommonsSpec
import org.codice.ddf.admin.sources.utils.SourceUtilCommons
import org.codice.ddf.admin.sources.utils.SourceValidationUtils
import org.codice.ddf.internal.admin.configurator.actions.*
import org.junit.Ignore

class CreateWfsConfigurationSpec extends SourceCommonsSpec {

    static final List<Object> FUNCTION_PATH = [CreateWfsConfiguration.FIELD_NAME]

    static RESULT_ARGUMENT_PATH = [CreateWfsConfiguration.FIELD_NAME]

    static BASE_PATH = [RESULT_ARGUMENT_PATH].flatten()

    static CONFIG_PATH = [BASE_PATH, WfsSourceConfigurationField.DEFAULT_FIELD_NAME].flatten()

    static SOURCE_NAME_PATH = [CONFIG_PATH, SOURCE_NAME].flatten()

    static ENDPOINT_URL_PATH = [CONFIG_PATH, ENDPOINT_URL].flatten()

    static WFS_VERSION = WfsVersion.DEFAULT_FIELD_NAME;

    static WFS_VERSION_PATH = [CONFIG_PATH, WFS_VERSION].flatten()

    static TEST_WFS_VERSION = WfsVersion.Wfs1.WFS_VERSION_1

    CreateWfsConfiguration createWfsConfiguration

    ConfiguratorFactory configuratorFactory

    ServiceActions serviceActions

    ServiceReader serviceReader

    Configurator configurator

    ConfiguratorSuite configuratorSuite

    ManagedServiceActions managedServiceActions

    FeatureActions featureActions

    FederatedSource federatedSource

    ServiceCommons serviceCommons

    SourceValidationUtils sourceValidationUtils

    def federatedSources = []

    def setup() {
        configurator = Mock(Configurator)
        serviceActions = Mock(ServiceActions)
        serviceReader = Mock(ServiceReader)
        managedServiceActions = Mock(ManagedServiceActions)
        featureActions = Mock(FeatureActions)

        federatedSource = Mock(FederatedSource)
        federatedSource.getId() >> TEST_SOURCENAME
        federatedSources.add(federatedSource)
        configuratorFactory = Mock(ConfiguratorFactory) {
            getConfigurator() >> configurator
        }

        configuratorSuite = Mock(ConfiguratorSuite)
        configuratorSuite.configuratorFactory >> configuratorFactory
        configuratorSuite.serviceActions >> serviceActions
        configuratorSuite.serviceReader >> serviceReader
        configuratorSuite.managedServiceActions >> managedServiceActions
        configuratorSuite.featureActions >> featureActions

        serviceCommons = new ServiceCommons(configuratorSuite)
        sourceValidationUtils = new SourceValidationUtils(new SourceUtilCommons(configuratorSuite), serviceCommons)

        createWfsConfiguration = new CreateWfsConfiguration(sourceValidationUtils, serviceCommons)
    }

    def 'Successfully create new WFS configuration'() {
        setup:
        serviceReader.getServices(_, _) >> []
        configurator.commit(_, _) >> mockReport(false)

        when:
        def report = createWfsConfiguration.execute(createWfsArgs(), FUNCTION_PATH)

        then:
        report.getResult() != null
        report.getResult().getValue()
    }

    def 'Fail to create new WFS config due to duplicate source name'() {
        setup:
        serviceReader.getServices(_, _) >> federatedSources

        when:
        def report = createWfsConfiguration.execute(createWfsArgs(), FUNCTION_PATH)

        then:
        report.getResult() == null
        report.getErrorMessages().size() == 1
        report.getErrorMessages().get(0).code == SourceMessages.DUPLICATE_SOURCE_NAME
        report.getErrorMessages().get(0).path == SOURCE_NAME_PATH
    }

    def 'Fail to create new WFS config due to failure to commit'() {
        setup:
        serviceReader.getServices(_, _) >> []

        when:
        def report = createWfsConfiguration.execute(createWfsArgs(), FUNCTION_PATH)

        then:
        configurator.commit(_, _) >> mockReport(true)
        !report.getResult().getValue()
        report.getErrorMessages().size() == 1
        report.getErrorMessages().get(0).code == DefaultMessages.FAILED_PERSIST
        report.getErrorMessages().get(0).path == RESULT_ARGUMENT_PATH
    }

    @Ignore
    // TODO: 8/23/17 phuffer - Remove ignore when feature starts correctly
    def 'Return false when wfs feature fails to start'() {
        when:
        serviceReader.getServices(_, _) >> []
        def report = createWfsConfiguration.execute(createWfsArgs(), FUNCTION_PATH)

        then:
        1 * configurator.commit(_, _) >> mockReport(true)
        !report.getResult().getValue()
        report.getErrorMessages().size() == 1
        report.getErrorMessages().get(0).path == RESULT_ARGUMENT_PATH
        report.getErrorMessages().get(0).code == DefaultMessages.FAILED_PERSIST
    }

    def 'Fail due to missing required fields'() {
        when:
        def report = createWfsConfiguration.execute(null, FUNCTION_PATH)

        then:
        report.getResult() == null
        report.getErrorMessages().size() == 3
        report.getErrorMessages().count {
            it.getCode() == DefaultMessages.MISSING_REQUIRED_FIELD
        } == 3
        report.getErrorMessages()*.getPath() == [SOURCE_NAME_PATH, ENDPOINT_URL_PATH, WFS_VERSION_PATH]
    }

    def 'Returns all the possible error codes correctly'() {
        setup:
        CreateWfsConfiguration createDuplicateNameConfig = new CreateWfsConfiguration(sourceValidationUtils, serviceCommons)
        serviceReader.getServices(_, _) >> federatedSources

        CreateWfsConfiguration createFailPersistConfig = new CreateWfsConfiguration(sourceValidationUtils, serviceCommons)
        serviceReader.getServices(_, _) >> []

        when:
        def errorCodes = createWfsConfiguration.getFunctionErrorCodes()
        def duplicateNameReport = createDuplicateNameConfig.execute(createWfsArgs(), FUNCTION_PATH)
        def createFailPersistReport = createFailPersistConfig.execute(createWfsArgs(), FUNCTION_PATH)

        then:
        errorCodes.size() == 2
        errorCodes.contains(duplicateNameReport.getErrorMessages().get(0).getCode())
        errorCodes.contains(createFailPersistReport.getErrorMessages().get(0).getCode())
    }

    def createWfsArgs() {
        def config = new WfsSourceConfigurationField().wfsVersion(TEST_WFS_VERSION)
                .endpointUrl('https://localhost:8993/geoserver/wfs').sourceName(TEST_SOURCENAME)
        config.credentials().username(TEST_USERNAME).password(TEST_PASSWORD)
        return [(WfsSourceConfigurationField.DEFAULT_FIELD_NAME): config.getValue()]
    }
}
