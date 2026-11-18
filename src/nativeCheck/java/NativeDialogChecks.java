import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.*;
import net.minecraft.client.input.*;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.*;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.world.level.GameType;

import java.lang.reflect.*;
import java.util.*;

/** Full native screen initialization; only window/GPU/network access is substituted. */
final class NativeDialogChecks {
    private static final sun.misc.Unsafe UNSAFE;
    private static int checks;
    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    public static class OfflineGui extends Gui {
        Screen selected;
        public OfflineGui() { super(null, null, null); }
        @Override public void setScreen(Screen screen) { selected = screen; }
    }
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }
    static boolean noScroll(Component body, Font font, int width, boolean footer, boolean verifyActions) throws Exception {
        net.minecraft.client.gui.screens.dialog.body.DialogBodyHandlers.bootstrap();
        Minecraft minecraft = (Minecraft) UNSAFE.allocateInstance(Minecraft.class);
        OfflineGui gui = (OfflineGui) UNSAFE.allocateInstance(OfflineGui.class);
        MultiPlayerGameMode mode = (MultiPlayerGameMode) UNSAFE.allocateInstance(MultiPlayerGameMode.class);
        set(Minecraft.class, null, "instance", minecraft);
        set(Minecraft.class, minecraft, "font", font);
        set(Minecraft.class, minecraft, "gui", gui);
        set(Minecraft.class, minecraft, "lastInputType", net.minecraft.client.InputType.MOUSE);
        minecraft.gameMode = mode;
        set(MultiPlayerGameMode.class, mode, "localPlayerMode", GameType.SPECTATOR);
        List<Identifier> sent = new ArrayList<>();
        DialogConnectionAccess connection = (DialogConnectionAccess) Proxy.newProxyInstance(
            NativeDialogChecks.class.getClassLoader(), new Class[]{DialogConnectionAccess.class},
            (proxy, method, args) -> {
                if (method.getName().equals("sendCustomAction")) { sent.add((Identifier) args[0]); return null; }
                if (method.getName().equals("serverLinks")) return net.minecraft.server.ServerLinks.EMPTY;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == void.class) return null;
                throw new UnsupportedOperationException(method.toString());
            });
        var exitClick = new ClickEvent.Custom(Identifier.parse("lumamenu:footer"), Optional.empty());
        var exit = new ActionButton(new CommonButtonData(Component.empty(), Optional.empty(), 60),
            Optional.of(new StaticAction(exitClick)));
        var common = new CommonDialogData(Component.literal(" "), Optional.empty(), footer, false,
            DialogAction.NONE, List.of(new net.minecraft.server.dialog.body.PlainMessage(body, width)), List.of());
        var dialog = new DialogListDialog(common, net.minecraft.core.HolderSet.direct(),
            footer ? Optional.of(exit) : Optional.empty(), 1, 1);
        var screen = new DialogListDialogScreen(null, dialog, connection);
        screen.width = 480; screen.height = 247;
        gui.selected = screen;
        Method init = DialogScreen.class.getDeclaredMethod("init");
        init.setAccessible(true); init.invoke(screen);
        var layout = (HeaderAndFooterLayout) get(DialogScreen.class, screen, "layout");
        var scroll = (ScrollableLayout) get(DialogScreen.class, screen, "bodyScroll");
        double[] max = {0}, moved = {0};
        scroll.visitChildren(child -> {
            if (child instanceof AbstractScrollArea area) {
                max[0] = Math.max(max[0], area.maxScrollAmount());
                area.mouseScrolled(10, 10, 0, -1);
                moved[0] = Math.max(moved[0], area.scrollAmount());
            }
        });
        if (verifyActions) {
            check(minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR, "real native spectator mode is selected");
            check(screen.shouldCloseOnEsc() == footer, "Esc option reaches native Dialog screen");
            screen.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0, 0));
            check(footer ? sent.equals(List.of(exitClick.id())) && gui.selected == null
                : sent.isEmpty() && gui.selected == screen, "real Esc key closes fallback only");
            if (!footer) {
                List<FocusableTextWidget> widgets = new ArrayList<>();
                ((net.minecraft.client.gui.layouts.Layout) get(ScrollableLayout.class, scroll, "content"))
                    .visitWidgets(child -> { if (child instanceof FocusableTextWidget widget) widgets.add(widget); });
                check(widgets.size() == 1, "one actual Dialog body widget");
                var widget = widgets.getFirst();
                // Body content is centered inside the native widget's four-pixel padding.
                int advance = font.width(font.split(body, width).getFirst());
                float x = widget.getX() + widget.getWidth() / 2f - advance / 2f + advance - 12 + 4.5f;
                float y = widget.getY() + 4 + 6 + 4.5f;
                sent.clear();
                var finder = new net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder(font, (int) x, (int) y);
                widget.visitLines(finder);
                boolean clicked = screen.mouseClicked(new MouseButtonEvent(x, y,
                    new MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
                System.out.println("X click widget=" + widget.getX() + "," + widget.getY() + " "
                    + widget.getWidth() + "x" + widget.getHeight() + " at=" + x + "," + y
                    + " style=" + finder.result() + " consumed=" + clicked + " sent=" + sent);
                check(clicked && sent.size() == 1 && sent.getFirst().getNamespace().equals("lumamenu"),
                    "native spectator Dialog X receives an actual mouse click");
            }
            System.out.println("Full Dialog footer=" + footer + " header=" + layout.getHeaderHeight()
                + " footerHeight=" + layout.getFooterHeight() + " viewport=" + layout.getContentHeight()
                + " maxScroll=" + max[0] + " wheel=" + moved[0]);
        }
        return max[0] == 0 && moved[0] == 0;
    }
    static int checks() { return checks; }
}
