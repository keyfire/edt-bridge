package io.github.keyfire.edtbridge.core;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
class MetadataOrderTest {
 @Test void insertsIntoAnExistingAlphabeticOrder() {
  assertEquals(1, MetadataOrder.insertionIndex(List.of("Alpha", "Gamma"), "Beta"));
  assertEquals(1, MetadataOrder.insertionIndex(List.of("Альфа", "Гамма"), "бета"));
  assertEquals(0, MetadataOrder.insertionIndex(List.of(), "First"));
 }
 @Test void preservesAnExplicitlyUnsortedOrder() {
  assertEquals(2, MetadataOrder.insertionIndex(List.of("Gamma", "Alpha"), "Beta"));
  assertEquals(2, MetadataOrder.insertionIndex(Arrays.asList("Alpha", null), "Beta"));
 }
 @Test void retainsExistingCaseInsensitiveEqualsBeforeTheNewItem() {
  assertEquals(1, MetadataOrder.insertionIndex(List.of("alpha", "Beta"), "Alpha"));
 }
}
