/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;

import org.elasticsearch.license.manager.ESLicenseManager;
import org.junit.BeforeClass;

import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.util.Map;

public class AbstractLicensingTestBase {

    protected static String pubKeyPath = null;
    protected static String priKeyPath = null;

    @BeforeClass
    public static void setup() throws Exception {
        pubKeyPath = getResourcePath("/public.key");
        priKeyPath = getResourcePath("/private.key");

    }

    public static String getTestPriKeyPath() throws Exception {
        return getResourcePath("/private.key");
    }

    public static String getTestPubKeyPath() throws Exception {
        return getResourcePath("/public.key");
    }

    private static String getResourcePath(String resource) throws Exception {
        URL url = ESLicenseManager.class.getResource(resource);
        return url.toURI().getPath();
    }

    public String generateSignedLicenses(Map<String, TestUtils.FeatureAttributes> map) throws IOException, ParseException {
        String licenseString = TestUtils.generateESLicenses(map);
        return TestUtils.runLicenseGenerationTool(licenseString, pubKeyPath, priKeyPath);
    }
}
