package ohi.andre.consolelauncher.commands.main.raw;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.managers.ContactManager;

public class refresh implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) {
        MainPack info = (MainPack) pack;
        info.appsManager.fill();
        info.aliasManager.reload();
        info.player.refresh(info.context);
        ContactManager.refreshContacts(info.contacts, info.context);

        return info.res.getString(R.string.output_refresh);
    }

    @Override
    public int helpRes() {
        return R.string.help_refresh;
    }

    @Override
    public int minArgs() {
        return 0;
    }

    @Override
    public int maxArgs() {
        return 0;
    }

    @Override
    public int[] argType() {
        return new int[0];
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public String onNotArgEnough(ExecutePack info, int nArgs) {
        return null;
    }

    @Override
    public String onArgNotFound(ExecutePack info, int index) {
        return null;
    }
}
