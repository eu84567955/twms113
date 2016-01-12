/* 
 * NPC   : Dev Doll
 * Map   : GMMAP
 */

importPackage(java.lang);

var status = 0;
var invs = Array(1, 5);
var invv;
var selected;
var slot_1 = Array();
var slot_2 = Array();
var statsSel;

function start() {
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;
    if (status == 1) {
        var bbb = false;
        var selStr = "注意! 點數商品丟掉之後就會到期了哦~~~\r\n你要丟哪個點裝 丟出來會丟在地上 丟寵物的話會直接消失\r\n請不要拿來交換楓幣之類的 避免發生爭執\r\n\r\n#b";
        for (var x = 0; x < invs.length; x++) {
            var inv = cm.getInventory(invs[x]);
            for (var i = 0; i <= inv.getSlotLimit(); i++) {
                if (x == 0) {
                    slot_1.push(i);
                } else {
                    slot_2.push(i);
                }
                var it = inv.getItem(i);
                if (it == null) {
                    continue;
                }
                var itemid = it.getItemId();
                if (!cm.isCash(itemid)) {
                    continue;
                }
                bbb = true;
                selStr += "#L" + (invs[x] * 1000 + i) + "##v" + itemid + "##t" + itemid + "##l\r\n";
            }
        }
        if (!bbb) {
            cm.sendOk("你沒有任何的點裝.");
            cm.dispose();
            return;
        }
        cm.sendSimple(selStr + "#k");
    } else if (status == 2) {
        invv = selection / 1000;
        selected = selection % 1000;

        var inzz = cm.getInventory(invv);
        if (invv == invs[0]) {
            statsSel = inzz.getItem(slot_1[selected]);
        } else {
            var sitem = slot_2[selected];
            if(sitem != null)
                statsSel = inzz.getItem(slot_2[selected]);
            else
                statsSel = null;
        }
        if (statsSel == null) {
            cm.sendOk("錯誤, 請再嘗試一次.");
            cm.dispose();
            return;
        }
        cm.sendGetNumber("你想要丟掉 #v" + statsSel.getItemId() + "##t" + statsSel.getItemId() + "#嗎?\r\n請輸入數量", 1, 1, statsSel.getQuantity());
    } else if (status == 3) {
        if (!cm.dropItem(selected, invv, selection)) {
            cm.sendOk("錯誤, 請再嘗試一次.");
            cm.dispose();
        } else {
            statsSel.setExpiration((System.currentTimeMillis() - 1));
            status = 0;
            action(1, 0, 0);
        }
    }
}
/*
var status = 6;

function start() {
    action(0,0,0);
}

function cancelled() {
    action(0,0,0);
}

function action(mode, type, selection) {
    switch (status) {
	case 5:
	    status = 6;
	    cm.sendNext("I'm afraid I can't let you go without entering the right password. (Yes I'm bad, blame me by all means) #bYou Cheater! I'm not stupid either.")
	    break;
	case 6:
	    status = 10;
	    cm.sendGetText("For the sake of privacy, please enter your first password which you use to login. #bYou have 2 tries from now.");
	    break;
	case 10: {
	    var pw = cm.getText();
	    if (cm.checkPassword(pw)) {
		cm.sendOk("You have authenticated yourself successfully, enjoy. Celino Online Staff. #b(Please do set a second password on the login page too)");
		cm.dispose();
	    } else {
		cm.sendOk("Invalid password, please try again.");
		status = 6;
	    }
	    break;
	}
    }
}*/
