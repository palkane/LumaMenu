package dev.lumamenu;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.chat.clickevent.CustomClickEvent;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.DialogListDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.action.StaticAction;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter;
import com.github.retrooper.packetevents.protocol.nbt.codec.NBTCodec;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;

/** Outgoing server Dialogs need more NBT accounting space than the default item-sized limit. */
final class MenuDialogPacket extends WrapperPlayServerShowDialog {
    MenuDialogPacket(PacketSendEvent event) { super(event); }
    MenuDialogPacket(Dialog dialog) { super(dialog); }

    @Override public NBT readNBTRaw() {
        return NBTCodec.readNBTFromBuffer(buffer, getServerVersion(),
            NBTLimiter.forBuffer(buffer, 16 * 1024 * 1024));
    }

    boolean isMenu() {
        if (!(getDialog() instanceof DialogListDialog list)) return false;
        if (list.getCommon().getBody().stream().anyMatch(body -> body instanceof PlainMessageDialogBody text
            && ownClose(text.getMessage().getContents()))) return true;
        if (list.getExitAction() == null) return false;
        var action = list.getExitAction().getAction();
        if (action instanceof DynamicCustomAction custom)
            return custom.getId().getNamespace().equals("lumamenu");
        return action instanceof StaticAction fixed && fixed.getClickEvent() instanceof CustomClickEvent custom
            && custom.getId().getNamespace().equals("lumamenu");
    }

    private static boolean ownClose(net.kyori.adventure.text.Component component) {
        if (HitSurface.CHROME_FONT.equals(component.font()) && component.clickEvent() != null
            && component.clickEvent().payload() instanceof net.kyori.adventure.text.event.ClickEvent.Payload.Custom custom
            && custom.key().namespace().equals("lumamenu")) return true;
        return component.children().stream().anyMatch(MenuDialogPacket::ownClose);
    }
}
