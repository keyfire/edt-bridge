package io.github.keyfire.edtbridge.edt;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ReturnValuesReuse;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
class CommonModuleSettingsTest {
 @Test void changesTypedExecutionFlagsAndReuseMode() {
  var module=MdClassFactory.eINSTANCE.createCommonModule();
  CommonModuleSettings.apply(module, Map.of("server",true,"externalConnection",true,
    "clientOrdinaryApplication",true,"returnValuesReuse","DuringSession"),false);
  assertTrue(module.isServer()); assertTrue(module.isExternalConnection());
  assertTrue(module.isClientOrdinaryApplication());
  assertEquals(ReturnValuesReuse.DURING_SESSION,module.getReturnValuesReuse());
 }
 @Test void validatesEveryPropertyBeforeWriting() {
  var module=MdClassFactory.eINSTANCE.createCommonModule();
  assertThrows(IllegalArgumentException.class,
    () -> CommonModuleSettings.apply(module,Map.of("server",true,"name","Changed"),false));
  assertFalse(module.isServer());
  assertThrows(IllegalArgumentException.class,
    () -> CommonModuleSettings.apply(module,Map.of("server","true"),false));
 }
 @Test void extendsTheAdoptedModuleControlWithoutReplacingItsBody() {
  var module=MdClassFactory.eINSTANCE.createCommonModule();
  var extension=MdClassExtensionFactory.eINSTANCE.createCommonModuleExtension();
  module.setExtension(extension);
  CommonModuleSettings.apply(module,Map.of(),true);
  assertEquals(MdPropertyState.EXTENDED,extension.getModule());
  assertNull(module.getModule());
 }
 @Test void refusesInvalidEnumAndAnUnadoptedExtensionRequest() {
  var module=MdClassFactory.eINSTANCE.createCommonModule();
  assertThrows(IllegalArgumentException.class,
    () -> CommonModuleSettings.apply(module,Map.of("returnValuesReuse","NotAValue"),false));
  assertThrows(IllegalArgumentException.class,
    () -> CommonModuleSettings.apply(module,Map.of(),true));
 }
}
