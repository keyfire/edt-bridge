/*
 * edt-bridge - a 1C:EDT bridge that exposes the live EDT model over MCP.
 * Copyright 2026 edt-bridge contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.keyfire.edtbridge.edt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.cmi.ICommandInterfaceAccessor;
import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.cmi.model.CommandsOrder;
import com._1c.g5.v8.dt.cmi.model.CommandsOrderFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacement;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacementFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibility;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibilityFragment;
import com._1c.g5.v8.dt.cmi.model.deriveddata.AbstractPanel;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CommandItem;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CommandItemGroup;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CommandInterfaceRoot;
import com._1c.g5.v8.dt.cmi.model.deriveddata.SubsystemCommandInterface;
import com._1c.g5.v8.dt.cmi.model.util.CmiModelUtil;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.mcore.CommandGroup;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;
import com._1c.g5.v8.dt.metadata.mdclass.ForRoleType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.wiring.ServiceAccess;
import io.github.keyfire.edtbridge.core.CommandInterfaceEdit;

/**
 * The command interface of a subsystem section through the live model. Reading takes EDT's own
 * derived data, so the answer is the section as the client builds it: which commands it shows, in
 * which group, in which order, and to whom. Writing changes {@code CommandInterface.cmi} the way the
 * command interface editor does: visibility in common and by role, the group of a command, the
 * order of a group, and a return to the default of either.
 */
public final class CommandInterfaceGateway {
    public static final class CommandView {
        public String command;
        public Boolean common;
        public Map<String, Boolean> roles;
        public boolean visibilityCustomized;
        public boolean groupCustomized;
    }

    public static final class GroupView {
        public String group;
        public String panel;
        public boolean customized;
        public List<CommandView> commands = new ArrayList<>();
    }

    public static final class Change {
        public String command;
        public String property;
        public Object before;
        public Object after;
    }

    public static final class Result {
        public boolean ok;
        public boolean applied;
        public boolean serialized;
        public String project;
        public String subsystem;
        public Boolean adoptedSubsystem;
        public String plan;
        public String message;
        public String warning;
        public List<GroupView> groups;
        public List<String> commandSources;
        public List<Change> changes;
    }

    public Result read(String projectName, String subsystemFqn) {
        Result result = new Result();
        result.project = projectName;
        result.subsystem = subsystemFqn;
        try {
            IBmModel model = model(projectName);
            ICommandInterfaceAccessor accessor = accessor();
            model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.commandInterface.read") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Subsystem subsystem = MetadataSettingsGateway.subsystem(tx, subsystemFqn);
                    result.adoptedSubsystem = RoleRightsGateway.adopted(subsystem);
                    SubsystemCommandInterface section = section(accessor.getRoot(tx), subsystem, subsystemFqn);
                    result.groups = groups(section);
                    result.commandSources = new ArrayList<>();
                    for (MdObject source : section.getCommandSources()) result.commandSources.add(RoleRightsGateway.fqn(source));
                    return null;
                }
            });
            result.ok = true;
            int commands = 0;
            for (GroupView group : result.groups) commands += group.commands.size();
            result.message = commands + " command(s) in " + result.groups.size() + " group(s) of " + subsystemFqn;
        } catch (RuntimeException failure) {
            result.ok = false;
            result.message = GatewaySupport.describeCause(failure);
        }
        return result;
    }

    public Result modify(String projectName, String subsystemFqn, List<CommandInterfaceEdit.CommandChange> commands,
            CommandInterfaceEdit.Order order, boolean apply) {
        Result result = new Result();
        result.project = projectName;
        result.subsystem = subsystemFqn;
        try {
            if ((commands == null || commands.isEmpty()) && order == null) {
                throw new IllegalArgumentException("nothing to change: give commands, order or both");
            }
            List<CommandInterfaceEdit.CommandChange> changes = commands == null ? List.of() : commands;
            IBmModel model = model(projectName);
            ICommandInterfaceAccessor accessor = accessor();
            model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.commandInterface.inspect") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Subsystem subsystem = MetadataSettingsGateway.subsystem(tx, subsystemFqn);
                    result.adoptedSubsystem = RoleRightsGateway.adopted(subsystem);
                    Section section = new Section(section(accessor.getRoot(tx), subsystem, subsystemFqn), tx);
                    result.changes = plan(section, changes, order, tx);
                    return null;
                }
            });
            result.ok = true;
            result.plan = result.changes.size() + " change(s) of the command interface of " + subsystemFqn;
            if (!apply || result.changes.isEmpty()) {
                result.message = result.changes.isEmpty() ? "The command interface already is as requested"
                        : "dry-run: nothing written";
                return result;
            }
            List<String> exported = new ArrayList<>();
            model.execute(new AbstractBmTask<Object>("edt-bridge.commandInterface.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Subsystem subsystem = MetadataSettingsGateway.subsystem(tx, subsystemFqn);
                    Section section = new Section(section(accessor.getRoot(tx), subsystem, subsystemFqn), tx);
                    CommandInterface cmi = subsystem.getCommandInterface() instanceof CommandInterface existing ? existing : null;
                    // An adopted subsystem without its own CommandInterface.cmi answers a placeholder that
                    // is not attached to the model; changes made to it would never reach a file.
                    if (cmi == null || !attached(cmi)) {
                        ITopObjectFqnGenerator names = ServiceAccess.get(ITopObjectFqnGenerator.class);
                        cmi = CmiModelUtil.createCommandInterface(tx,
                                names.generateExternalPropertyFqn(subsystem, MdClassPackage.Literals.SUBSYSTEM__COMMAND_INTERFACE));
                        subsystem.setCommandInterface(cmi);
                        exported.add(RoleRightsGateway.fqn(subsystem));
                    }
                    for (CommandInterfaceEdit.CommandChange change : changes) {
                        Command command = section.command(change.command());
                        if (change.reset().contains("visibility")) resetVisibility(cmi, change.command());
                        if (change.visible() != null) {
                            setVisibility(cmi, command, change.command(), visibility(section, change, tx));
                        }
                        if (change.reset().contains("group")) place(cmi, command, change.command(), null);
                        if (change.group() != null) {
                            CommandGroup target = section.group(change.group(), tx);
                            boolean byDefault = Objects.equals(change.group(), groupName(CmiModelUtil.getDefaultCommandGroup(command)));
                            place(cmi, command, change.command(), byDefault ? null : target);
                        }
                    }
                    if (order != null) {
                        List<Command> ordered = new ArrayList<>();
                        for (String name : order.commands()) ordered.add(section.command(name));
                        order(cmi, section.group(order.group(), tx), ordered);
                    }
                    if (cmi instanceof IBmObject bm && bm.bmIsTop() && bm.bmGetFqn() != null) exported.add(0, bm.bmGetFqn());
                    return null;
                }
            });
            result.applied = true;
            export(model, exported, result);
            result.message = "Updated the command interface of " + subsystemFqn + ": " + result.changes.size() + " change(s)";
        } catch (RuntimeException failure) {
            result.ok = false;
            result.message = (result.applied ? "The model changed, but export failed: " : "Nothing written: ")
                    + GatewaySupport.describeCause(failure);
        }
        return result;
    }

    private static boolean attached(CommandInterface cmi) {
        return cmi instanceof IBmObject bm && !bm.bmIsTransient() && bm.bmGetEngine() != null;
    }

    /** The derived section: commands and groups by name, with the panel each group belongs to. */
    private static final class Section {
        final Map<String, CommandItem> items = new LinkedHashMap<>();
        final Map<String, String> groupOf = new LinkedHashMap<>();
        final Map<String, CommandGroup> groups = new LinkedHashMap<>();
        final Map<String, String> panelOf = new LinkedHashMap<>();
        final Map<String, List<String>> members = new LinkedHashMap<>();

        Section(SubsystemCommandInterface section, IBmTransaction tx) {
            for (GroupOf group : allGroups(section)) {
                String name = groupName(group.group.getCommandGroup());
                groups.put(name, group.group.getCommandGroup());
                panelOf.put(name, group.panel);
                List<String> names = new ArrayList<>();
                for (CommandItem item : group.group.getItems()) {
                    String command = commandName(item.getCommand());
                    items.put(command, item);
                    groupOf.put(command, name);
                    names.add(command);
                }
                members.put(name, names);
            }
        }

        Command command(String name) {
            CommandItem item = items.get(name);
            if (item == null) {
                throw new IllegalArgumentException("command not in this section: " + name + "; the section has: "
                        + (items.isEmpty() ? "nothing" : String.join(", ", items.keySet())));
            }
            return item.getCommand();
        }

        CommandGroup group(String name, IBmTransaction tx) {
            CommandGroup group = groups.get(name);
            if (group != null) return group;
            if (name.startsWith("CommandGroup.") && tx.getTopObjectByFqn(name) instanceof CommandGroup custom) return custom;
            throw new IllegalArgumentException("command group not found: " + name + "; this section has: " + String.join(", ", groups.keySet()));
        }

        String panel(String group, IBmTransaction tx) {
            String panel = panelOf.get(group);
            if (panel != null) return panel;
            return panelOfCategory(String.valueOf(CmiModelUtil.getCommandGroupCategory(group(group, tx))));
        }
    }

    private record GroupOf(CommandItemGroup group, String panel) { }

    private static List<GroupOf> allGroups(SubsystemCommandInterface section) {
        List<GroupOf> groups = new ArrayList<>();
        var navigation = section.getNavigationPanel();
        if (navigation != null) {
            add(groups, navigation.getImportantGroup(), "navigation");
            add(groups, navigation.getOrdinaryGroup(), "navigation");
            add(groups, navigation.getSeeAlsoGroup(), "navigation");
            addCustom(groups, navigation, "navigation");
        }
        var actions = section.getActionPanel();
        if (actions != null) {
            add(groups, actions.getCreateGroup(), "actions");
            add(groups, actions.getReportsGroup(), "actions");
            add(groups, actions.getToolsGroup(), "actions");
            addCustom(groups, actions, "actions");
        }
        return groups;
    }

    private static void add(List<GroupOf> groups, CommandItemGroup group, String panel) {
        if (group != null && group.getCommandGroup() != null) groups.add(new GroupOf(group, panel));
    }

    private static void addCustom(List<GroupOf> groups, AbstractPanel panel, String name) {
        for (CommandItemGroup group : panel.getCustomGroups()) add(groups, group, name);
    }

    private static List<GroupView> groups(SubsystemCommandInterface section) {
        List<GroupView> views = new ArrayList<>();
        for (GroupOf group : allGroups(section)) {
            GroupView view = new GroupView();
            view.group = groupName(group.group.getCommandGroup());
            view.panel = group.panel;
            view.customized = group.group.isCustomized();
            for (CommandItem item : group.group.getItems()) {
                CommandView command = new CommandView();
                command.command = commandName(item.getCommand());
                AdjustableBoolean visible = item.getVisibility();
                command.common = visible == null ? null : visible.isCommon();
                command.roles = roles(visible);
                command.visibilityCustomized = item.isVisibilityCustomized();
                command.groupCustomized = item.isGroupCustomized();
                view.commands.add(command);
            }
            views.add(view);
        }
        return views;
    }

    /** What changes, checked against the section before anything is written. */
    private static List<Change> plan(Section section, List<CommandInterfaceEdit.CommandChange> changes,
            CommandInterfaceEdit.Order order, IBmTransaction tx) {
        List<Change> plan = new ArrayList<>();
        for (CommandInterfaceEdit.CommandChange change : changes) {
            Command command = section.command(change.command());
            CommandItem item = section.items.get(change.command());
            if (change.visible() != null || change.reset().contains("visibility")) {
                Map<String, Object> before = visibilityView(item.getVisibility());
                Map<String, Object> after = change.visible() != null
                        ? visibilityView(visibility(section, change, tx))
                        : visibilityView(CmiModelUtil.getDefaultCommandVisible(command));
                if (!before.equals(after) || change.reset().contains("visibility") && item.isVisibilityCustomized()) {
                    plan.add(change(change.command(), "visible", before, after));
                }
            }
            String current = section.groupOf.get(change.command());
            String target = change.group() != null ? change.group()
                    : change.reset().contains("group") ? groupName(CmiModelUtil.getDefaultCommandGroup(command)) : null;
            if (target != null) {
                String from = section.panel(current, tx);
                String to = section.panel(target, tx);
                if (!Objects.equals(from, to)) {
                    throw new IllegalArgumentException(change.command() + " belongs to the " + from + " panel; "
                            + target + " is in the " + to + " panel");
                }
                if (!Objects.equals(current, target) || change.reset().contains("group") && item.isGroupCustomized()) {
                    plan.add(change(change.command(), "group", current, target));
                }
            }
        }
        if (order != null) {
            section.group(order.group(), tx);
            List<String> current = section.members.getOrDefault(order.group(), List.of());
            for (String name : order.commands()) section.command(name);
            CommandInterfaceEdit.checkOrder(order.commands(), current, order.group());
            if (!current.equals(order.commands())) plan.add(change(order.group(), "order", current, order.commands()));
        }
        return plan;
    }

    private static Change change(String command, String property, Object before, Object after) {
        Change change = new Change();
        change.command = command;
        change.property = property;
        change.before = before;
        change.after = after;
        return change;
    }

    /** The requested visibility on top of the current one: an omitted part keeps its value. */
    private static AdjustableBoolean visibility(Section section, CommandInterfaceEdit.CommandChange change, IBmTransaction tx) {
        AdjustableBoolean current = section.items.get(change.command()).getVisibility();
        AdjustableBoolean visible = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        Boolean common = change.visible().common();
        visible.setCommon(common != null ? common : current != null && current.isCommon());
        if (change.visible().roles() == null) {
            if (current != null) {
                for (ForRoleType role : current.getFor()) visible.getFor().add(forRole(role.getRole(), role.isValue()));
            }
        } else {
            for (Map.Entry<String, Boolean> role : change.visible().roles().entrySet()) {
                if (!(tx.getTopObjectByFqn(role.getKey()) instanceof Role found)) {
                    throw new IllegalArgumentException("Role not found: " + role.getKey()
                            + "; in an extension the role must be the extension's own or adopted");
                }
                visible.getFor().add(forRole(found, role.getValue()));
            }
        }
        return visible;
    }

    private static ForRoleType forRole(Role role, boolean value) {
        ForRoleType forRole = MdClassFactory.eINSTANCE.createForRoleType();
        forRole.setRole(role);
        forRole.setValue(value);
        return forRole;
    }

    private static Map<String, Object> visibilityView(AdjustableBoolean visible) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("common", visible != null && visible.isCommon());
        view.put("roles", roles(visible));
        return view;
    }

    private static Map<String, Boolean> roles(AdjustableBoolean visible) {
        Map<String, Boolean> roles = new LinkedHashMap<>();
        if (visible == null) return roles;
        for (ForRoleType role : visible.getFor()) roles.put(RoleRightsGateway.fqn(role.getRole()), role.isValue());
        return roles;
    }

    /** Sets the visibility of the command, creating its fragment when it has none. */
    static void setVisibility(CommandInterface cmi, Command command, String name, AdjustableBoolean visible) {
        CommandsVisibility visibility = cmi.getCommandsVisibility();
        if (visibility == null) {
            visibility = CmiFactory.eINSTANCE.createCommandsVisibility();
            cmi.setCommandsVisibility(visibility);
        }
        CommandsVisibilityFragment fragment = null;
        for (CommandsVisibilityFragment existing : visibility.getVisibilityFragments()) {
            if (name.equals(commandName(existing.getCommand()))) fragment = existing;
        }
        if (fragment == null) {
            fragment = CmiFactory.eINSTANCE.createCommandsVisibilityFragment();
            fragment.setCommand(command);
            visibility.getVisibilityFragments().add(fragment);
        }
        fragment.setVisible(visible);
    }

    /** Drops the command's visibility fragment, so the default of the command applies again. */
    static void resetVisibility(CommandInterface cmi, String name) {
        CommandsVisibility visibility = cmi.getCommandsVisibility();
        if (visibility == null) return;
        visibility.getVisibilityFragments().removeIf(fragment -> name.equals(commandName(fragment.getCommand())));
        if (visibility.getVisibilityFragments().isEmpty()) cmi.setCommandsVisibility(null);
    }

    /**
     * Moves the command to the target group, or back to its default group when the target is null.
     * The command leaves every placement and order fragment first, as the editor's task does: an
     * order of the group it left would otherwise still name it.
     */
    static void place(CommandInterface cmi, Command command, String name, CommandGroup target) {
        CommandsPlacement placement = cmi.getCommandsPlacement();
        if (placement != null) {
            for (CommandsPlacementFragment fragment : placement.getPlacementFragments()) {
                fragment.getCommands().removeIf(existing -> name.equals(commandName(existing)));
            }
            placement.getPlacementFragments().removeIf(fragment -> fragment.getCommands().isEmpty());
        }
        CommandsOrder order = cmi.getCommandsOrder();
        if (order != null) {
            for (CommandsOrderFragment fragment : order.getOrderFragments()) {
                fragment.getCommands().removeIf(existing -> name.equals(commandName(existing)));
            }
            order.getOrderFragments().removeIf(fragment -> fragment.getCommands().isEmpty());
            if (order.getOrderFragments().isEmpty()) cmi.setCommandsOrder(null);
        }
        if (target != null) {
            if (placement == null) {
                placement = CmiFactory.eINSTANCE.createCommandsPlacement();
                cmi.setCommandsPlacement(placement);
            }
            CommandsPlacementFragment fragment = null;
            for (CommandsPlacementFragment existing : placement.getPlacementFragments()) {
                if (groupName(target).equals(groupName(existing.getGroup()))) fragment = existing;
            }
            if (fragment == null) {
                fragment = CmiFactory.eINSTANCE.createCommandsPlacementFragment();
                fragment.setGroup(target);
                placement.getPlacementFragments().add(fragment);
            }
            fragment.getCommands().add(command);
        }
        if (cmi.getCommandsPlacement() != null && cmi.getCommandsPlacement().getPlacementFragments().isEmpty()) {
            cmi.setCommandsPlacement(null);
        }
    }

    /** Replaces the order of one group. */
    static void order(CommandInterface cmi, CommandGroup group, List<Command> commands) {
        CommandsOrder order = cmi.getCommandsOrder();
        if (order == null) {
            order = CmiFactory.eINSTANCE.createCommandsOrder();
            cmi.setCommandsOrder(order);
        }
        CommandsOrderFragment fragment = null;
        for (CommandsOrderFragment existing : order.getOrderFragments()) {
            if (groupName(group).equals(groupName(existing.getGroup()))) fragment = existing;
        }
        if (fragment == null) {
            fragment = CmiFactory.eINSTANCE.createCommandsOrderFragment();
            fragment.setGroup(group);
            order.getOrderFragments().add(fragment);
        }
        fragment.getCommands().clear();
        fragment.getCommands().addAll(commands);
    }

    /** The command the way CommandInterface.cmi names it: Catalog.Products.StandardCommand.OpenList. */
    static String commandName(Command command) {
        if (command instanceof StandardCommand standard && standard.eContainer() != null) {
            return RoleRightsGateway.fqn(standard.eContainer()) + ".StandardCommand." + standard.getName();
        }
        if (command instanceof BasicCommand basic) {
            EObject owner = basic.eContainer();
            if (owner == null || owner.eContainer() == null && !(owner instanceof MdObject)) return RoleRightsGateway.fqn(basic);
            if (owner instanceof MdObject && !"Configuration".equals(owner.eClass().getName())) {
                return RoleRightsGateway.fqn(owner) + ".Command." + basic.getName();
            }
            return RoleRightsGateway.fqn(basic);
        }
        return command == null ? "null" : CmiModelUtil.getCommandName(command);
    }

    /** The group the way CommandInterface.cmi names it: NavigationPanelImportant, CommandGroup.Sales. */
    static String groupName(CommandGroup group) {
        if (group instanceof StandardCommandGroup standard) return standard.getName();
        if (group instanceof com._1c.g5.v8.dt.metadata.mdclass.CommandGroup custom) return "CommandGroup." + custom.getName();
        return group == null ? "null" : CmiModelUtil.getCommandGroupName(group);
    }

    private static String panelOfCategory(String category) {
        return category.toUpperCase(java.util.Locale.ROOT).startsWith("NAVIGATION") ? "navigation" : "actions";
    }

    private static SubsystemCommandInterface section(CommandInterfaceRoot root, Subsystem subsystem, String fqn) {
        if (root != null) {
            for (SubsystemCommandInterface section : root.getAllSubsystemCommandInterfaces()) {
                Subsystem owner = section.getSubsystem();
                if (owner == subsystem || owner != null && Objects.equals(owner.getUuid(), subsystem.getUuid())) return section;
            }
        }
        throw new IllegalArgumentException(fqn + " has no section in the command interface: it is not included in "
                + "the command interface, or EDT has not computed its derived data yet");
    }

    private static ICommandInterfaceAccessor accessor() {
        ICommandInterfaceAccessor accessor = ServiceAccess.get(ICommandInterfaceAccessor.class);
        if (accessor == null) throw new IllegalStateException("EDT command interface service is unavailable");
        return accessor;
    }

    private static IBmModel model(String projectName) {
        if (projectName == null || projectName.isBlank()) throw new IllegalArgumentException("projectName is required");
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) throw new IllegalArgumentException("Project not found or closed: " + projectName);
        IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = manager == null ? null : manager.getModel(project);
        if (model == null) throw new IllegalArgumentException("No BM model for project: " + projectName);
        return model;
    }

    private static void export(IBmModel model, List<String> fqns, Result result) {
        IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
        var project = manager == null ? null : manager.getDtProject(model);
        boolean serialized = project != null && !fqns.isEmpty();
        for (String fqn : fqns) serialized &= project != null && manager.forceExport(project, fqn);
        result.serialized = serialized;
        if (!result.serialized) result.warning = "The model changed but serialization of " + fqns
                + " was not confirmed; inspect the working tree";
    }
}
