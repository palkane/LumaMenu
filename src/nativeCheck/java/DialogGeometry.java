import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.*;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.world.level.GameType;

import java.lang.reflect.*;
import java.util.*;

/** Where the real 26.3 Dialog screens put the plain-message body for each close style LumaMenu can send. */
final class DialogGeometry {
    enum Mode {
        /** Dialog list without footer: corner-× pages and calibration. */
        CORNER,
        /** Notice whose single action is the native close button: corner-close false pages. */
        NOTICE,
        /** Dialog list with exit footer: the previous corner-close false transport, kept as a fault control. */
        LIST_FOOTER
    }
    record Layout(int header, int footer, int viewport, int bodyX, int bodyY, int bodyHeight, double maxScroll,
                  boolean escape, List<Identifier> escapeSent) {}

    private static final Identifier EXIT = Identifier.parse("lumamenu:footer");

    static Layout measure(Component body, Font font, int width, Mode mode, int screenWidth, int screenHeight)
            throws Exception {
        net.minecraft.client.gui.screens.dialog.body.DialogBodyHandlers.bootstrap();
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        var gui = (NativeDialogChecks.OfflineGui) unsafe.allocateInstance(NativeDialogChecks.OfflineGui.class);
        MultiPlayerGameMode gameMode = (MultiPlayerGameMode) unsafe.allocateInstance(MultiPlayerGameMode.class);
        set(Minecraft.class, null, "instance", minecraft);
        set(Minecraft.class, minecraft, "font", font);
        set(Minecraft.class, minecraft, "gui", gui);
        set(Minecraft.class, minecraft, "lastInputType", net.minecraft.client.InputType.MOUSE);
        minecraft.gameMode = gameMode;
        set(MultiPlayerGameMode.class, gameMode, "localPlayerMode", GameType.SURVIVAL);
        List<Identifier> sent = new ArrayList<>();
        DialogConnectionAccess connection = (DialogConnectionAccess) Proxy.newProxyInstance(
            DialogGeometry.class.getClassLoader(), new Class[]{DialogConnectionAccess.class},
            (proxy, method, args) -> {
                if (method.getName().equals("sendCustomAction")) { sent.add((Identifier) args[0]); return null; }
                if (method.getName().equals("serverLinks")) return net.minecraft.server.ServerLinks.EMPTY;
                if (method.getReturnType() == boolean.class) return false;
                return null;
            });
        var exit = new ActionButton(new CommonButtonData(Component.literal("关闭"), Optional.empty(), 60),
            Optional.of(new StaticAction(new ClickEvent.Custom(EXIT, Optional.empty()))));
        boolean escape = mode != Mode.CORNER;
        var common = new CommonDialogData(Component.literal(" "), Optional.empty(), escape, false,
            DialogAction.NONE, List.of(new net.minecraft.server.dialog.body.PlainMessage(body, width)), List.of());
        DialogScreen<?> screen = switch (mode) {
            case CORNER -> new DialogListDialogScreen(null,
                new DialogListDialog(common, net.minecraft.core.HolderSet.direct(), Optional.empty(), 1, 1), connection);
            case LIST_FOOTER -> new DialogListDialogScreen(null,
                new DialogListDialog(common, net.minecraft.core.HolderSet.direct(), Optional.of(exit), 1, 1), connection);
            case NOTICE -> new SimpleDialogScreen<>(null, new NoticeDialog(common, exit), connection);
        };
        screen.width = screenWidth; screen.height = screenHeight;
        gui.selected = screen;
        Method init = DialogScreen.class.getDeclaredMethod("init");
        init.setAccessible(true); init.invoke(screen);
        var layout = (HeaderAndFooterLayout) get(DialogScreen.class, screen, "layout");
        var scroll = (ScrollableLayout) get(DialogScreen.class, screen, "bodyScroll");
        double[] max = {0};
        scroll.visitChildren(child -> { if (child instanceof AbstractScrollArea area) max[0] = Math.max(max[0], area.maxScrollAmount()); });
        List<FocusableTextWidget> widgets = new ArrayList<>();
        ((net.minecraft.client.gui.layouts.Layout) get(ScrollableLayout.class, scroll, "content"))
            .visitWidgets(child -> { if (child instanceof FocusableTextWidget widget) widgets.add(widget); });
        var widget = widgets.getFirst();
        boolean closesOnEsc = screen.shouldCloseOnEsc();
        if (closesOnEsc) screen.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0, 0));
        Screen after = gui.selected;
        return new Layout(layout.getHeaderHeight(), layout.getFooterHeight(), layout.getContentHeight(),
            widget.getX(), widget.getY(), widget.getHeight(), max[0], closesOnEsc && after == null, List.copyOf(sent));
    }

    static Component rows(int count) {
        return Component.literal(String.join("\n", Collections.nCopies(count, " ")));
    }
    static boolean sentExit(Layout layout) { return layout.escapeSent().equals(List.of(EXIT)); }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }
}
