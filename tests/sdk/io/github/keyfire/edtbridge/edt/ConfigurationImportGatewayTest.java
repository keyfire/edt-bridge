package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationImportGatewayTest {
    @TempDir Path directory;

    @Test
    void acceptsAPlatformConfigurationDump() throws Exception {
        Files.writeString(directory.resolve("Configuration.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses" version="2.20">
                    <Configuration uuid="11111111-1111-1111-1111-111111111111"/>
                </MetaDataObject>
                """);
        assertEquals(directory.toRealPath(), ConfigurationImportGateway.sourcePath(directory.toString()));
        assertEquals("2.20", ConfigurationImportGateway.xmlFormat(directory));
    }

    @Test
    void acceptsANonemptyCfPathAndRejectsAnExistingEdtProjectAsADump() throws Exception {
        Path binary = Files.write(directory.resolve("sample.CF"), new byte[] {1});
        Files.writeString(directory.resolve(".project"), "<projectDescription/>");
        assertEquals(binary.toRealPath(), ConfigurationImportGateway.sourcePath(binary.toString()));
        assertThrows(IOException.class, () -> ConfigurationImportGateway.sourcePath(directory.toString()));
        Path empty = Files.createFile(directory.resolve("empty.cf"));
        assertThrows(IOException.class, () -> ConfigurationImportGateway.sourcePath(empty.toString()));
    }

    @Test
    void doesNotTreatAnExternalObjectDumpAsAConfiguration() throws Exception {
        Files.writeString(directory.resolve("Configuration.xml"), """
                <MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses" version="2.20">
                    <ExternalDataProcessor/>
                </MetaDataObject>
                """);
        assertThrows(IOException.class, () -> ConfigurationImportGateway.xmlFormat(directory));
    }
}
