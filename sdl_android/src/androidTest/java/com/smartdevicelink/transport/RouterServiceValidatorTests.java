package com.smartdevicelink.transport;

import android.content.pm.PackageManager;

import com.smartdevicelink.AndroidTestCase2;

import org.junit.Assert;

public class RouterServiceValidatorTests extends AndroidTestCase2 {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testIsTrustedPackageTest() {
        RouterServiceValidator validator = new RouterServiceValidator(mContext);
        isTrustedPackageTestInternal(validator, "cn.co.toyota.sdl.capp.toyota");
        isTrustedPackageTestInternal(validator, "cn.co.toyota.sdl.capp.lexus");
        isTrustedPackageTestInternal(validator, "com.xevokk.jsuz.capp");
        isTrustedPackageTestInternal(validator, "jp.co.daihatsu.dconnect");
        isTrustedPackageTestInternal(validator, "app.mylexus.lexus.com.mylexus");
        isTrustedPackageTestInternal(validator, "app.mytoyota.toyota.com.mytoyota");
        isTrustedPackageTestInternal(validator, "au.com.toyota.mytoyota.app");
        isTrustedPackageTestInternal(validator, "au.com.lexus.mylexus.app");
        isTrustedPackageTestInternal(validator, "com.xevo.samplecapp");
    }

    private void isTrustedPackageTestInternal(RouterServiceValidator validator, String packageName) {
        boolean isTrusted = validator.isTrustedPackageForTest(packageName, mContext.getPackageManager());
        if (!isTrusted) {
            Assert.fail("isTrustedPackage failed for: " + packageName);
        }
    }
}
