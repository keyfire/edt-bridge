package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

class ConfigurationFeatureTest {
    @Test void aRoleGoesToTheRolesAndNotToTheDefaultRoles() {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        assertSame(MdClassPackage.Literals.CONFIGURATION__ROLES,
                MetadataWriteGateway.findConfigFeature(configuration, MdClassPackage.Literals.ROLE));
    }

    @Test void anyOtherClassTakesItsOnlyList() {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        assertSame(MdClassPackage.Literals.CONFIGURATION__CATALOGS,
                MetadataWriteGateway.findConfigFeature(configuration, MdClassPackage.Literals.CATALOG));
        assertSame(MdClassPackage.Literals.CONFIGURATION__COMMON_MODULES,
                MetadataWriteGateway.findConfigFeature(configuration, MdClassPackage.Literals.COMMON_MODULE));
        assertNull(MetadataWriteGateway.findConfigFeature(null, MdClassPackage.Literals.ROLE));
    }
}
