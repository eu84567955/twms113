package handling.cashshop.handler;

import java.sql.SQLException;
import java.util.Map;
import java.util.HashMap;

import constants.GameConstants;
import client.MapleClient;
import client.MapleCharacter;
import client.MapleCharacterUtil;
import client.inventory.MapleInventoryType;
import client.inventory.MapleRing;
import client.inventory.MapleInventoryIdentifier;
import client.inventory.IItem;
import client.inventory.Item;
import handling.cashshop.CashShopServer;
import handling.channel.ChannelServer;
import handling.world.CharacterTransfer;
import handling.world.World;
import java.util.ArrayList;
import java.util.List;
import server.CashItemFactory;
import server.CashItemInfo;
import server.MTSStorage;
import server.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import tools.MaplePacketCreator;
import tools.packet.MTSCSPacket;
import tools.Pair;
import tools.data.input.SeekableLittleEndianAccessor;

public class CashShopOperation {

    public static void LeaveCashShop(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        CashShopServer.getPlayerStorageMTS().deregisterPlayer(chr);
        CashShopServer.getPlayerStorage().deregisterPlayer(chr);
        c.updateLoginState(MapleClient.LOGIN_SERVER_TRANSITION, c.getSessionIPAddress());

        try {
            World.channelChangeData(new CharacterTransfer(chr), chr.getId(), c.getChannel());
            c.sendPacket(MaplePacketCreator.getChannelChange(ChannelServer.getInstance(c.getChannel()).getGatewayIP(), ChannelServer.getInstance(c.getChannel()).getPort()));
        } finally {
            c.disconnect(true, true);
            c.setPlayer(null);
            c.setReceiving(false);
        }
    }

    public static void EnterCashShop(final int playerid, final MapleClient client) {
        CharacterTransfer transfer = CashShopServer.getPlayerStorage().getPendingCharacter(playerid);
        boolean mts = false;
        if (transfer == null) {
            transfer = CashShopServer.getPlayerStorageMTS().getPendingCharacter(playerid);
            mts = true;
            if (transfer == null) {
                client.disconnect(false, false);
                return;
            }
        }
        MapleCharacter chr = MapleCharacter.ReconstructChr(transfer, client, false);

        chr.reloadCSPoints();
        client.setPlayer(chr);
        client.setAccID(chr.getAccountID());
        client.loadAccountData(chr.getAccountID());

        if (!client.CheckIPAddress()) { // Remote hack
            client.disconnect(false, true);
            return;
        }

        final int state = client.getLoginState();
        boolean allowLogin = false;
        if (state == MapleClient.LOGIN_SERVER_TRANSITION || state == MapleClient.CHANGE_CHANNEL) {
            //if (!World.isCharacterListConnected(client.loadCharacterNames(client.getWorld()))) {
            if (!World.isConnected(chr.getName())) {
                allowLogin = true;
            }
        }
        // System.out.println( state );

        if (!allowLogin) {
            client.disconnect(false, false);
            return;
        }

        client.updateLoginState(MapleClient.LOGIN_CS_LOGGEDIN, client.getSessionIPAddress());

        if (mts) {
            CashShopServer.getPlayerStorageMTS().registerPlayer(chr);
            client.sendPacket(MTSCSPacket.startMTS(chr, client));
            //MTSOperation.MTSUpdate(MTSStorage.getInstance().getCart(client.getPlayer().getId()), client);
        } else {
            CashShopServer.getPlayerStorage().registerPlayer(chr);
            client.sendPacket(MTSCSPacket.warpCS(client));
            sendCashShopUpdate(client);
        }
    }

    public static void sendCashShopUpdate(final MapleClient c) {
        c.getPlayer().reloadCSPoints();
        c.sendPacket(MTSCSPacket.showCashShopAcc(c));
        c.sendPacket(MTSCSPacket.showGifts(c));
        refreshCashShop(c);
        c.sendPacket(MTSCSPacket.sendShowWishList(c.getPlayer()));
    }
    
    public static void sendWebSite(final MapleClient c) {
        c.getPlayer().dropMessage(1, "儲值詳情請參見社團。");
        refreshCashShop(c);
    }
    public static void randomes(final MapleClient c) {
        refreshCashShop(c);
    }

    public static void CouponCode(final String code, final MapleClient c) {
        boolean validcode = false;
        int type = -1, item = -1, size = -1;

        validcode = MapleCharacterUtil.getNXCodeValid(code.toUpperCase(), validcode);

        if (validcode) {
            type = MapleCharacterUtil.getNXCodeType(code);
            item = MapleCharacterUtil.getNXCodeItem(code);
            size = MapleCharacterUtil.getNXCodeSize(code);
            if (type != 4) {
                try {
                    MapleCharacterUtil.setNXCodeUsed(c.getPlayer().getName(), code);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }

            /*
             * 類型說明！
             * 基本上，這使得優惠券代碼做不同的東西！
             *
             * Type 1: GASH點數
             * Type 2: 楓葉點數
             * Type 3: 物品x數量(默認1個)
             * Type 4: 楓幣
             */
            int maplePoints = 0, mesos = 0, as = 0;
            String cc = "";
            switch (type) {
                case 1:
                    c.getPlayer().modifyCSPoints(1, item, false);
                    maplePoints = item;
                    cc = "GASH";
                    break;
                case 2:
                    c.getPlayer().modifyCSPoints(2, item, false);
                    maplePoints = item;
                    cc = "楓葉點數";
                    break;
                case 3:
                    MapleInventoryManipulator.addById(c, item, (short) size, "優待卷禮品.", null, -1);
                    as = 1;
                    break;
                case 4:
                    c.getPlayer().gainMeso(item, false);
                    mesos = item;
                    cc = "楓幣";
                    break;
            }
            if (as == 1) {
                //c.sendPacket(MTSCSPacket.showCouponRedeemedItem(itemz, mesos, maplePoints, c));
                c.getPlayer().dropMessage(1, "已成功使用優待卷獲得" + MapleItemInformationProvider.getInstance().getName(item) + " x" + size + "。");
            } else {
                c.getPlayer().dropMessage(1, "已成功使用優待卷獲得" + item + cc);
            }
        } else {
            c.sendPacket(MTSCSPacket.sendCSFail(validcode ? 0xA5 : 0xA7)); //A1, 9F
        }

        refreshCashShop(c);
    }

    public static final void BuyCashItem(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        final int action = slea.readByte();

        switch (action) {
            case 30:
            case 3: {   // Buy Item
                final int useNX = slea.readByte() + 1;
                final int snCS = slea.readInt();
                CashItemInfo cItem = CashItemFactory.getInstance().getItem(snCS);
                List<CashItemInfo> ccc = null;
                if (action == 30 && cItem != null) {
                    ccc = CashItemFactory.getInstance().getPackageItems(cItem.getId());
                }
                boolean canBuy = true;
                int errorCode = 0;

                if (cItem == null || (action == 30 && (ccc == null || ccc != null && ccc.isEmpty())) || useNX < 1 || useNX > 2) {
                    canBuy = false;
                } else if (!cItem.onSale()) {
                    canBuy = false;
                    errorCode = 225;
                } else if (chr.getCSPoints(useNX) < cItem.getPrice()) {
                    if (useNX == 1) {
                        errorCode = 168;
                    } else {
                        errorCode = 225;
                    }
                    canBuy = false;
                } else if (!cItem.genderEquals(c.getPlayer().getGender())) {
                    canBuy = false;
                    errorCode = 186;
                } else if (c.getPlayer().getCashInventory().getItemsSize() >= 100) {
                    canBuy = false;
                    errorCode = 166;
                }
                if (canBuy && cItem != null) {
                    for (int i : GameConstants.cashBlock) {
                        if (cItem.getId() == i) {
                            c.getPlayer().dropMessage(1, GameConstants.getCashBlockedMsg(cItem.getId()));
                            refreshCashShop(c);
                            return;
                        }
                    }

                    if (action == 3) { // one item 
                        chr.modifyCSPoints(useNX, -cItem.getPrice(), false);
                        IItem itemz = chr.getCashInventory().toItem(cItem);
                        if (itemz != null && itemz.getUniqueId() > 0 && itemz.getItemId() == cItem.getId() && itemz.getQuantity() == cItem.getCount()) {
                            chr.getCashInventory().addToInventory(itemz);
                            c.sendPacket(MTSCSPacket.showBoughtCashItem(itemz, cItem.getSN(), c.getAccID()));
                        } else {
                            c.sendPacket(MTSCSPacket.sendCSFail(errorCode));
                        }
                    } else { // package
                        Map<Integer, IItem> ccz = new HashMap<Integer, IItem>();
                        for (CashItemInfo i : ccc) {
                            for (int iz : GameConstants.cashBlock) {
                                if (i.getId() == iz) {
                                    continue;
                                }
                            }
                            IItem itemz = c.getPlayer().getCashInventory().toItem(i);
                            if (itemz == null || itemz.getUniqueId() <= 0 || itemz.getItemId() != i.getId()) {
                                continue;
                            }
                            ccz.put(i.getSN(), itemz);
                            c.getPlayer().getCashInventory().addToInventory(itemz);
                        }
                        chr.modifyCSPoints(1, -cItem.getPrice(), false);
                        c.sendPacket(MTSCSPacket.showBoughtCashPackage(ccz, c.getAccID()));
                    }

                } else {
                    c.sendPacket(MTSCSPacket.sendCSFail(errorCode));
                }

                refreshCashShop(c);
                break;
            }
            case 4: { // gift
                final String secondPassword = slea.readMapleAsciiString();
                final int sn = slea.readInt();
                final String characterName = slea.readMapleAsciiString();
                final String message = slea.readMapleAsciiString();

                boolean canBuy = true;
                int errorCode = 0;

                CashItemInfo cItem = CashItemFactory.getInstance().getItem(sn);
                IItem item = chr.getCashInventory().toItem(cItem);

                Pair<Integer, Pair<Integer, Integer>> info = MapleCharacterUtil.getInfoByName(characterName, c.getPlayer().getWorld());

                if (cItem == null) {
                    canBuy = false;
                } else if (!cItem.onSale()) {
                    canBuy = false;
                    errorCode = 225;
                } else if (chr.getCSPoints(1) < cItem.getPrice()) {
                    errorCode = 168;
                    canBuy = false;
                } else if (!c.check2ndPassword(secondPassword)) {
                    canBuy = false;
                    errorCode = 197;
                } else if (message.getBytes().length < 1 || message.getBytes().length > 74) {
                    canBuy = false;
                    errorCode = 225;
                } else if (info == null) {
                    canBuy = false;
                    errorCode = 172;
                } else if (info.getRight().getLeft() == c.getAccID() || info.getLeft() == c.getPlayer().getId()) {
                    canBuy = false;
                    errorCode = 171;
                } else if (!cItem.genderEquals(info.getRight().getRight())) {
                    canBuy = false;
                    errorCode = 176;
                }
                if (canBuy && info != null && cItem != null) {
                    for (int i : GameConstants.cashBlock) {
                        if (cItem.getId() == i) {
                            c.getPlayer().dropMessage(1, GameConstants.getCashBlockedMsg(cItem.getId()));
                            return;
                        }
                    }
                    c.getPlayer().getCashInventory().gift(info.getLeft(), c.getPlayer().getName(), message, cItem.getSN(), MapleInventoryIdentifier.getInstance());
                    c.getPlayer().modifyCSPoints(1, -cItem.getPrice(), false);

                    c.sendPacket(MTSCSPacket.sendGift(characterName, cItem, cItem.getPrice() / 2, false));
                    chr.sendNote(characterName, chr.getName() + " 送了你禮物! 趕快去商城確認看看.", (byte) 0); //fame or not
                    MapleCharacter receiver = c.getChannelServer().getPlayerStorage().getCharacterByName(characterName);
                    if (receiver != null) {
                        receiver.showNote();
                    }
                    //c.sendPacket(MTSCSPacket.sendGift(cItem.getPrice(), cItem.getId(), cItem.getCount(), characterName), f);
                } else {
                    c.sendPacket(MTSCSPacket.sendCSFail(errorCode));
                }
                refreshCashShop(c);
                break;
            }

            case 5: { //Wish List
                chr.clearWishlist();
                if (slea.available() < 40) {
                    c.sendPacket(MTSCSPacket.sendCSFail(0));
                    refreshCashShop(c);
                    return;
                }
                int[] wishlist = new int[10];
                for (int i = 0; i < 10; i++) {
                    wishlist[i] = slea.readInt();
                }
                chr.setWishlist(wishlist);
                c.sendPacket(MTSCSPacket.setWishList(chr));
                refreshCashShop(c);
                break;
            }
            ////////////////////
            case 6: {
                //slea.skip(1);
                final int useNX = slea.readByte() + 1;
                final boolean coupon = slea.readByte() > 0;
                if (coupon) {
                    final MapleInventoryType type = getInventoryType(slea.readInt());

                    if (chr.getCSPoints(useNX) >= 100 && chr.getInventory(type).getSlotLimit() < 89) {
                        chr.modifyCSPoints(useNX, -100, false);
                        chr.getInventory(type).addSlot((byte) 8);
                        chr.dropMessage(1, "欄位已經被擴充至 " + chr.getInventory(type).getSlotLimit() + " 格");
                    } else {                     
                        //c.sendPacket(MTSCSPacket.sendCSFail(0xA4));
                        chr.dropMessage(1, "欄位無法再進行擴充");
                    }
                } else {
                    final MapleInventoryType type = MapleInventoryType.getByType(slea.readByte());

                    if (chr.getCSPoints(useNX) >= 100 && chr.getInventory(type).getSlotLimit() < 93) {
                        chr.modifyCSPoints(useNX, -100, false);
                        chr.getInventory(type).addSlot((byte) 4);
                        chr.dropMessage(1, "欄位已經被擴充至 " + chr.getInventory(type).getSlotLimit() + " 格");
                    } else {
                        //c.sendPacket(MTSCSPacket.sendCSFail(0xA4));
                        chr.dropMessage(1, "欄位無法再進行擴充");
                    }
                }
                refreshCashShop(c);
                break;
            }
            case 7: {
                final int useNX = slea.readByte() + 1;
                if (chr.getCSPoints(useNX) >= 100 && chr.getStorage().getSlots() < 45) {
                    chr.modifyCSPoints(useNX, -100, false);
                    chr.getStorage().increaseSlots((byte) 4);
                    chr.getStorage().saveToDB();
                    //c.sendPacket(MTSCSPacket.increasedStorageSlots(chr.getStorage().getSlots()));
                    chr.dropMessage(1, "欄位已經被擴充至 " + chr.getInventory(type).getSlotLimit() + " 格");
                } else {
                    //c.sendPacket(MTSCSPacket.sendCSFail(0xA4));
                    chr.dropMessage(1, "欄位無法再進行擴充");
                }
                refreshCashShop(c);
                break;
            }

            case 8: {
                slea.readByte();
                CashItemInfo item = CashItemFactory.getInstance().getItem(slea.readInt());
                int slots = c.getCharacterSlots();
                if (item == null || c.getPlayer().getCSPoints(1) < item.getPrice() || slots > 15) {
                    c.sendPacket(MTSCSPacket.sendCSFail(0));
                    refreshCashShop(c);
                    return;
                }
                c.getPlayer().modifyCSPoints(1, -item.getPrice(), false);
                if (c.gainCharacterSlot()) {
                    c.sendPacket(MTSCSPacket.increasedStorageSlots(slots + 1));
                } else {
                    c.sendPacket(MTSCSPacket.sendCSFail(0));
                }
                refreshCashShop(c);
                break;
            }

            case 13: {
                IItem item = c.getPlayer().getCashInventory().findByCashId((int) slea.readLong());
                if (item != null && item.getQuantity() > 0 && MapleInventoryManipulator.checkSpace(c, item.getItemId(), item.getQuantity(), item.getOwner())) {
                    IItem item_ = item.copy();
                    short pos = MapleInventoryManipulator.addbyItem(c, item_, true);
                    if (pos >= 0) {
                        if (item_.getPet() != null) {
                            item_.getPet().setInventoryPosition(pos);
                            c.getPlayer().addPet(item_.getPet());
                        }
                        c.getPlayer().getCashInventory().removeFromInventory(item);
                        c.sendPacket(MTSCSPacket.confirmFromCSInventory(item_, pos));
                    } else {
                        c.sendPacket(MTSCSPacket.sendCSFail(0xB1));
                    }
                } else {
                    c.sendPacket(MTSCSPacket.sendCSFail(0xB1));
                }
                refreshCashShop(c);
                break;
            }

            case 14: {
                int uniqueid = (int) slea.readLong();
                MapleInventoryType type = MapleInventoryType.getByType(slea.readByte());

                IItem item = c.getPlayer().getInventory(type).findByUniqueId(uniqueid);
                if (item != null && item.getQuantity() > 0 && item.getUniqueId() > 0 && c.getPlayer().getCashInventory().getItemsSize() < 100) {
                    IItem item_ = item.copy();
                    c.getPlayer().getInventory(type).removeItem(item.getPosition(), item.getQuantity(), false);
                    int sn = CashItemFactory.getInstance().getSnByItemItd(item_.getItemId());
                    if (item_.getPet() != null) {
                        c.getPlayer().removePetCS(item_.getPet());
                    }
                    item_.setPosition((byte) 0);
                    c.getPlayer().getCashInventory().addToInventory(item_);
                    //warning: this d/cs
                    c.sendPacket(MTSCSPacket.confirmToCSInventory(item, c.getAccID(), sn));
                } else {
                    c.sendPacket(MTSCSPacket.sendCSFail(0xB1));
                }
                refreshCashShop(c);
                break;

            }

            case 32: { //1 meso
                final CashItemInfo item = CashItemFactory.getInstance().getItem(slea.readInt());
                if (item == null || !MapleItemInformationProvider.getInstance().isQuestItem(item.getId())) {
                    c.sendPacket(MTSCSPacket.sendCSFail(0));
                    refreshCashShop(c);
                    return;
                } else if (c.getPlayer().getMeso() < item.getPrice()) {
                    c.sendPacket(MTSCSPacket.sendCSFail(0xB8));
                    refreshCashShop(c);
                    return;
                } else if (c.getPlayer().getInventory(GameConstants.getInventoryType(item.getId())).getNextFreeSlot() < 0) {
                    c.sendPacket(MTSCSPacket.sendCSFail(0xB1));
                    refreshCashShop(c);
                    return;
                }
                for (int iz : GameConstants.cashBlock) {
                    if (item.getId() == iz) {
                        c.getPlayer().dropMessage(1, GameConstants.getCashBlockedMsg(item.getId()));
                        refreshCashShop(c);
                        return;
                    }
                }
                byte pos = MapleInventoryManipulator.addId(c, item.getId(), (short) item.getCount(), null);
                if (pos < 0) {
                    c.sendPacket(MTSCSPacket.sendCSFail(0xB1));
                    refreshCashShop(c);
                    return;
                }
                chr.gainMeso(-item.getPrice(), false);
                c.sendPacket(MTSCSPacket.showBoughtCSQuestItem(item.getPrice(), (short) item.getCount(), pos, item.getId()));
                refreshCashShop(c);
                break;
            }

            case 29: // crush ring
            case 35: { // friendRing
                 /*
                 E6 00 
                 23 
                 08 00 5D 31 31 31 31 31 31 31 
                 EB E8 3E 01 
                 09 00 71 77 65 71 77 65 71 65 71 
                 04 00 58 44 44 0A
                 */
                final String secondPassword = slea.readMapleAsciiString();
                final int sn = slea.readInt();
                final String partnerName = slea.readMapleAsciiString();
                final String message = slea.readMapleAsciiString();
                final CashItemInfo cItem = CashItemFactory.getInstance().getItem(sn);
                Pair<Integer, Pair<Integer, Integer>> info = MapleCharacterUtil.getInfoByName(partnerName, c.getPlayer().getWorld());

                boolean canBuy = true;
                int errorCode = 0;

                if (cItem == null) {
                    canBuy = false;
                } else if (!cItem.onSale()) {
                    canBuy = false;
                    errorCode = 225;
                } else if (chr.getCSPoints(1) < cItem.getPrice()) {
                    errorCode = 168;
                    canBuy = false;
                } else if (!c.check2ndPassword(secondPassword)) {
                    canBuy = false;
                    errorCode = 197;
                } else if (message.getBytes().length < 1 || message.getBytes().length > 74) {
                    canBuy = false;
                    errorCode = 225;
                } else if (info == null) {
                    canBuy = false;
                    errorCode = 172;
                } else if (info.getRight().getLeft() == c.getAccID() || info.getLeft() == c.getPlayer().getId()) {
                    canBuy = false;
                    errorCode = 171;
                } else if (!cItem.genderEquals(info.getRight().getRight())) {
                    canBuy = false;
                    errorCode = 176;
                } else if (!GameConstants.isEffectRing(cItem.getId())) {
                    canBuy = false;
                    errorCode = 0;
                } else if (info.getRight().getRight() == c.getPlayer().getGender() && action == 29) {
                    canBuy = false;
                    errorCode = 191;
                }
                if (canBuy && info != null && cItem != null) {
                    for (int i : GameConstants.cashBlock) { //just incase hacker
                        if (cItem.getId() == i) {
                            c.getPlayer().dropMessage(1, GameConstants.getCashBlockedMsg(cItem.getId()));
                            refreshCashShop(c);
                            return;
                        }
                    }
                    int err = MapleRing.createRing(cItem.getId(), c.getPlayer(), partnerName, message, info.getLeft(), cItem.getSN());
                    if (err != 1) {
                        c.sendPacket(MTSCSPacket.sendCSFail(0)); //9E v75
                        refreshCashShop(c);
                        return;
                    }

                    c.getPlayer().modifyCSPoints(1, -cItem.getPrice(), false);

                    chr.sendNote(partnerName, chr.getName() + " 送了你禮物! 趕快去商城確認看看.", (byte) 0); //fame or not
                    MapleCharacter receiver = c.getChannelServer().getPlayerStorage().getCharacterByName(partnerName);
                    if (receiver != null) {
                        receiver.showNote();
                    }

                } else {
                    c.sendPacket(MTSCSPacket.sendCSFail(errorCode));
                }

                refreshCashShop(c);
                break;
            }

            default:
                c.sendPacket(MTSCSPacket.sendCSFail(0));
                refreshCashShop(c);
        }

    }

    private static final MapleInventoryType getInventoryType(final int id) {
        switch (id) {
            case 50200075:
                return MapleInventoryType.EQUIP;
            case 50200074:
                return MapleInventoryType.USE;
            case 50200073:
                return MapleInventoryType.ETC;
            default:
                return MapleInventoryType.UNDEFINED;
        }
    }

    private static final void refreshCashShop(MapleClient c) {
        c.sendPacket(MTSCSPacket.showCashInventory(c));
        c.sendPacket(MTSCSPacket.showNXMapleTokens(c.getPlayer()));
        c.sendPacket(MTSCSPacket.enableCSUse());
        c.getPlayer().getCashInventory().checkExpire(c);
    }
}
