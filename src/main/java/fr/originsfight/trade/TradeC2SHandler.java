package fr.originsfight.trade;

import fr.originsfight.OriginsFightCore;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public class TradeC2SHandler implements PluginMessageListener {

    public static final String CHANNEL_C2S = "CUSTOM:TRADE_C2S";

    // Packet ids C2S
    private static final int TRADE_OFFER   = 0xA0;
    private static final int TRADE_TAKE    = 0xA1;
    private static final int TRADE_CONFIRM = 0xA2;
    private static final int TRADE_CANCEL  = 0xA3;
    private static final int TRADE_MONEY   = 0xA4;
    private static final int TRADE_PB      = 0xA5;

    private final TradeManager manager = TradeManager.getInstance();
    private final OriginsFightCore plugin;

    public TradeC2SHandler(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_C2S.equals(channel)) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            int packetId = readVarInt(in);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try { handle(player, packetId, in); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private void handle(Player player, int packetId, DataInputStream in) throws IOException {
        TradeSession session = manager.getSession(player);
        if (session == null || !session.isActive()) return;

        switch (packetId) {
            case TRADE_OFFER: {
                int invSlot = readVarInt(in);
                session.offerFromInventory(player, invSlot);
                break;
            }
            case TRADE_TAKE: {
                int index = readVarInt(in);
                session.takeBackFromOffer(player, index);
                break;
            }
            case TRADE_CONFIRM:
                if (session.getPlayerA().equals(player)) session.confirmA();
                else session.confirmB();
                break;
            case TRADE_CANCEL:
                manager.removeSession(session);
                session.cancel(player);
                break;
            case TRADE_MONEY: {
                long amount = in.readLong();
                session.setMoneyOffer(player, amount);
                break;
            }
            case TRADE_PB: {
                int amount = readVarInt(in);
                session.setPBOffer(player, amount);
                break;
            }
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, shift = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new IOException("EOF");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift >= 35) throw new IOException("VarInt overflow");
        }
    }
}
