package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.jupiter.api.Test;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.RootComparisonNode;
import com._1c.g5.v8.dt.md.compare.DistributionSettingsComparisonNode;
import com._1c.g5.v8.dt.md.compare.MdObjectComparisonNode;
import com._1c.g5.v8.dt.md.compare.SupportSettingsComparisonNode;

import io.github.keyfire.edtbridge.core.ConfigurationComparison;

class ConfigurationComparisonGatewayTest {
    private static final ComparisonSide MAIN = ComparisonSide.MAIN;
    private static final ComparisonSide OTHER = ComparisonSide.OTHER;
    private static final ComparisonSide ANCESTOR = ComparisonSide.COMMON_ANCESTOR;

    @Test
    void includesObjectsChangedOnlyInSupportOrDistributionOptions() throws Exception {
        var changed = new ComparisonFlags();
        changed.setHasChanged(MAIN, OTHER);
        var support = node(SupportSettingsComparisonNode.class, 1, null, changed);
        var distribution = node(DistributionSettingsComparisonNode.class, 2, null, changed);
        var picture = node(MdObjectComparisonNode.class, 3, "CommonPicture.Icon", changed, support, distribution);
        var snapshot = snapshot(false, picture);

        assertEquals(new ConfigurationComparison.Summary(1, 0, 0, 1), snapshot.summary());
        assertEquals(List.of("SupportSettingsComparisonNode", "DistributionSettingsComparisonNode"),
                snapshot.details(0, List.of(), 0, 10).items().stream().map(ConfigurationComparison.Change::name).toList());
    }

    @Test
    void countsOrderChangesInBothTwoAndThreeWayComparisons() throws Exception {
        var order = new ComparisonFlags();
        order.setHasOrderChanged(MAIN, OTHER);
        var same = new ComparisonFlags();
        var ancestorOrder = new ComparisonFlags();
        ancestorOrder.setHasOrderChanged(OTHER, ANCESTOR);
        var first = node(MdObjectComparisonNode.class, 1, "Enum.Status.EnumValue.First", order);
        var second = node(MdObjectComparisonNode.class, 2, "Enum.Status.EnumValue.Second", same);
        var third = node(MdObjectComparisonNode.class, 3, "Enum.Status.EnumValue.Third", ancestorOrder);
        var firstReport = snapshot(false, first, second, third);
        var secondReport = snapshot(true, first, second, third);

        assertEquals(new ConfigurationComparison.Summary(1, 0, 0, 1), firstReport.summary());
        assertEquals(new ConfigurationComparison.Summary(2, 0, 0, 2), secondReport.summary());
    }

    private static ConfigurationComparison snapshot(boolean threeWay, ComparisonNode... objects) throws Exception {
        RootComparisonNode root = node(RootComparisonNode.class, 0, null, new ComparisonFlags(), objects);
        IComparisonSession session = (IComparisonSession) Proxy.newProxyInstance(
                IComparisonSession.class.getClassLoader(), new Class<?>[]{IComparisonSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRootNode" -> root;
                    case "isThreeWay" -> threeWay;
                    case "getRelatedFeature" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var method = ConfigurationComparisonGateway.class.getDeclaredMethod(
                "snapshot", IComparisonSession.class, IProgressMonitor.class);
        method.setAccessible(true);
        return (ConfigurationComparison) method.invoke(null, session, new NullProgressMonitor());
    }

    private static <T extends ComparisonNode> T node(Class<T> type, long id, String name, ComparisonFlags flags,
            ComparisonNode... children) {
        var eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(type.getSimpleName());
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getChildren" -> new BasicEList<>(List.of(children));
                    case "getMainSymlink", "getOtherSymlink", "getCommonAncestorSymlink" -> name;
                    case "getComparisonFlags" -> flags;
                    case "bmGetId" -> id;
                    case "eClass" -> eClass;
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }
}
