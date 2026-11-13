package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommand;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommandSource;

class CommandInterfaceGatewayTest {
    private final Catalog products = catalog("Products");
    private final StandardCommand openList = standard(products, "OpenList");
    private final StandardCommand create = standard(products, "Create");
    private final StandardCommandGroup important = group("NavigationPanelImportant");
    private final StandardCommandGroup ordinary = group("NavigationPanelOrdinary");

    @Test void commandsAndGroupsAreNamedTheWayTheCmiFileNamesThem() {
        assertEquals("Catalog.Products.StandardCommand.OpenList", CommandInterfaceGateway.commandName(openList));
        CatalogCommand print = MdClassFactory.eINSTANCE.createCatalogCommand();
        print.setName("Print");
        products.getCommands().add(print);
        assertEquals("Catalog.Products.Command.Print", CommandInterfaceGateway.commandName(print));
        CommonCommand report = MdClassFactory.eINSTANCE.createCommonCommand();
        report.setName("Report");
        assertEquals("CommonCommand.Report", CommandInterfaceGateway.commandName(report));
        assertEquals("NavigationPanelImportant", CommandInterfaceGateway.groupName(important));
        var custom = MdClassFactory.eINSTANCE.createCommandGroup();
        custom.setName("Sales");
        assertEquals("CommandGroup.Sales", CommandInterfaceGateway.groupName(custom));
    }

    @Test void visibilityIsSetOncePerCommandAndAResetLeavesNoEmptyList() {
        CommandInterface cmi = CmiFactory.eINSTANCE.createCommandInterface();
        CommandInterfaceGateway.setVisibility(cmi, openList, "Catalog.Products.StandardCommand.OpenList", visible(false));
        CommandInterfaceGateway.setVisibility(cmi, openList, "Catalog.Products.StandardCommand.OpenList", visible(true));
        assertEquals(1, cmi.getCommandsVisibility().getVisibilityFragments().size());
        assertTrue(cmi.getCommandsVisibility().getVisibilityFragments().get(0).getVisible().isCommon());

        CommandInterfaceGateway.resetVisibility(cmi, "Catalog.Products.StandardCommand.OpenList");
        assertNull(cmi.getCommandsVisibility());
    }

    @Test void aMovedCommandLeavesItsOldPlacementAndOrderAndTheDefaultLeavesNoFragment() {
        CommandInterface cmi = CmiFactory.eINSTANCE.createCommandInterface();
        CommandInterfaceGateway.order(cmi, ordinary, List.of(create, openList));
        CommandInterfaceGateway.place(cmi, openList, "Catalog.Products.StandardCommand.OpenList", important);

        assertEquals("NavigationPanelImportant", CommandInterfaceGateway.groupName(cmi.getCommandsPlacement().getPlacementFragments().get(0).getGroup()));
        assertEquals(List.of(openList), cmi.getCommandsPlacement().getPlacementFragments().get(0).getCommands());
        assertEquals(List.of(create), cmi.getCommandsOrder().getOrderFragments().get(0).getCommands());

        CommandInterfaceGateway.place(cmi, openList, "Catalog.Products.StandardCommand.OpenList", null);
        assertNull(cmi.getCommandsPlacement());
    }

    @Test void anOrderReplacesTheOrderOfItsGroupOnly() {
        CommandInterface cmi = CmiFactory.eINSTANCE.createCommandInterface();
        CommandInterfaceGateway.order(cmi, ordinary, List.of(create, openList));
        CommandInterfaceGateway.order(cmi, important, List.of(openList));
        CommandInterfaceGateway.order(cmi, ordinary, List.of(openList, create));
        assertEquals(2, cmi.getCommandsOrder().getOrderFragments().size());
        assertEquals(List.of(openList, create), cmi.getCommandsOrder().getOrderFragments().get(0).getCommands());
    }

    private static AdjustableBoolean visible(boolean common) {
        AdjustableBoolean visible = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        visible.setCommon(common);
        return visible;
    }

    private static Catalog catalog(String name) {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        return catalog;
    }

    private static StandardCommand standard(Catalog owner, String name) {
        StandardCommand command = MdClassFactory.eINSTANCE.createStandardCommand();
        command.setName(name);
        ((StandardCommandSource) owner).getStandardCommands().add(command);
        return command;
    }

    private static StandardCommandGroup group(String name) {
        StandardCommandGroup group = McoreFactory.eINSTANCE.createStandardCommandGroup();
        group.setName(name);
        return group;
    }
}
