/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.common.base.Predicate;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.license.AbstractLicensingTestBase;
import org.elasticsearch.license.core.ESLicense;
import org.elasticsearch.license.licensor.ESLicenseSigner;
import org.elasticsearch.license.plugin.action.put.PutLicenseRequest;
import org.elasticsearch.license.plugin.action.put.PutLicenseRequestBuilder;
import org.elasticsearch.license.plugin.action.put.PutLicenseResponse;
import org.elasticsearch.license.plugin.core.LicensesManagerService;
import org.elasticsearch.license.plugin.core.LicensesService;
import org.elasticsearch.license.plugin.core.LicensesStatus;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.InternalTestCluster;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.license.AbstractLicensingTestBase.getTestPriKeyPath;
import static org.elasticsearch.license.AbstractLicensingTestBase.getTestPubKeyPath;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.SUITE;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.TEST;
import static org.hamcrest.CoreMatchers.equalTo;

@ClusterScope(scope = TEST, numDataNodes = 3, numClientNodes = 0)
public class LicensesPluginIntegrationTests extends ElasticsearchIntegrationTest {

    private final int trialLicenseDurationInSeconds = 5;

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.settingsBuilder()
                .put("plugins.load_classpath_plugins", false)
                .put("test_consumer_plugin.trial_license_duration_in_seconds", trialLicenseDurationInSeconds)
                .put("plugin.types", LicensePlugin.class.getName() + "," + TestConsumerPlugin.class.getName())
                .build();
    }

    @Override
    protected Settings transportClientSettings() {
        // Plugin should be loaded on the transport client as well
        return nodeSettings(0);
    }

    @Test
    public void test() throws Exception {
        // managerService should report feature to be enabled on all data nodes
        assertThat(awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object o) {
                for (LicensesManagerService managerService : licensesManagerServices()) {
                    if (!managerService.enabledFeatures().contains(TestPluginService.FEATURE_NAME)) {
                        return false;
                    }
                }
                return true;
            }
        }, 2, TimeUnit.SECONDS), equalTo(true));


        // consumer plugin service should return enabled on all data nodes
        /*assertThat(awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object o) {
                for (TestPluginService pluginService : consumerPluginServices()) {
                    if (!pluginService.enabled()) {
                        return false;
                    }
                }
                return true;
            }
        }, 2, TimeUnit.SECONDS), equalTo(true));*/
        assertConsumerPluginEnableNotification(2);


        // consumer plugin should notify onDisabled on all data nodes (expired trial license)
        assertThat(awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object o) {
                for (TestPluginService pluginService : consumerPluginServices()) {
                    if(pluginService.enabled()) {
                        return false;
                    }

                }
                return true;
            }
        }, trialLicenseDurationInSeconds * 2, TimeUnit.SECONDS), equalTo(true));

        // consumer plugin should notify onEnabled on all data nodes (signed license)
        /*
        ESLicense license = generateSignedLicense(TestPluginService.FEATURE_NAME, TimeValue.timeValueSeconds(5));
        final PutLicenseResponse putLicenseResponse = new PutLicenseRequestBuilder(client().admin().cluster()).setLicense(Lists.newArrayList(license)).get();
        assertThat(putLicenseResponse.isAcknowledged(), equalTo(true));
        assertThat(putLicenseResponse.status(), equalTo(LicensesStatus.VALID));

        logger.info(" --> put signed license");
        assertThat(awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object o) {
                for (TestPluginService pluginService : consumerPluginServices()) {
                    if (!pluginService.enabled()) {
                        return false;
                    }
                }
                return true;
            }
        }, 2, TimeUnit.SECONDS), equalTo(true));

        assertThat(awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object o) {
                for (TestPluginService pluginService : consumerPluginServices()) {
                    if (pluginService.enabled()) {
                        return false;
                    }
                }
                return true;
            }
        }, 5, TimeUnit.SECONDS), equalTo(true));
        */
    }

    private void assertConsumerPluginDisableNotification(int timeoutInSec) throws InterruptedException {
        assertConsumerPluginNotification(false, timeoutInSec);
    }
    private void assertConsumerPluginEnableNotification(int timeoutInSec) throws InterruptedException {
        assertConsumerPluginNotification(true, timeoutInSec);
    }

    private void assertConsumerPluginNotification(final boolean expectedEnabled, int timeoutInSec) throws InterruptedException {
        assertThat(awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object o) {
                for (TestPluginService pluginService : consumerPluginServices()) {
                    if (expectedEnabled != pluginService.enabled()) {
                        return false;
                    }
                }
                return true;
            }
        }, timeoutInSec, TimeUnit.SECONDS), equalTo(true));
    }

    private ESLicense generateSignedLicense(String feature, TimeValue expiryDate) throws Exception {
        final ESLicense licenseSpec = ESLicense.builder()
                .uid(UUID.randomUUID().toString())
                .feature(feature)
                .expiryDate(expiryDate.getMillis())
                .issueDate(System.currentTimeMillis())
                .type("subscription")
                .subscriptionType("gold")
                .issuedTo("customer")
                .issuer("elasticsearch")
                .maxNodes(10)
                .build();

        ESLicenseSigner signer = new ESLicenseSigner(getTestPriKeyPath(), getTestPubKeyPath());
        return signer.sign(licenseSpec);
    }

    private Iterable<TestPluginService> consumerPluginServices() {
        final InternalTestCluster clients = internalCluster();
        return clients.getDataNodeInstances(TestPluginService.class);
    }

    private Iterable<LicensesManagerService> licensesManagerServices() {
        final InternalTestCluster clients = internalCluster();
        return clients.getDataNodeInstances(LicensesManagerService.class);
    }

    private LicensesManagerService masterLicenseManagerService() {
        final InternalTestCluster clients = internalCluster();
        return clients.getInstance(LicensesManagerService.class, clients.getMasterName());
    }
}
