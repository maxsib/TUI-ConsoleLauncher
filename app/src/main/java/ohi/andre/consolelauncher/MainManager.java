package ohi.andre.consolelauncher;

import android.content.Context;
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;

import java.util.regex.Pattern;

import ohi.andre.consolelauncher.commands.Command;
import ohi.andre.consolelauncher.commands.CommandGroup;
import ohi.andre.consolelauncher.commands.CommandTuils;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.commands.specific.RedirectCommand;
import ohi.andre.consolelauncher.managers.AliasManager;
import ohi.andre.consolelauncher.managers.AppsManager;
import ohi.andre.consolelauncher.managers.ContactManager;
import ohi.andre.consolelauncher.managers.ShellManager;
import ohi.andre.consolelauncher.managers.TerminalManager;
import ohi.andre.consolelauncher.managers.XMLPrefsManager;
import ohi.andre.consolelauncher.managers.music.MusicManager;
import ohi.andre.consolelauncher.tuils.StoppableThread;
import ohi.andre.consolelauncher.tuils.TimeManager;
import ohi.andre.consolelauncher.tuils.Tuils;
import ohi.andre.consolelauncher.tuils.interfaces.CommandExecuter;
import ohi.andre.consolelauncher.tuils.interfaces.Inputable;
import ohi.andre.consolelauncher.tuils.interfaces.OnRedirectionListener;
import ohi.andre.consolelauncher.tuils.interfaces.Outputable;
import ohi.andre.consolelauncher.tuils.interfaces.Redirectator;
import ohi.andre.consolelauncher.tuils.interfaces.Suggester;

/*Copyright Francesco Andreuzzi

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.*/

public class MainManager {

    private RedirectCommand redirect;
    private Redirectator redirectator = new Redirectator() {
        @Override
        public void prepareRedirection(RedirectCommand cmd) {
            redirect = cmd;

            if(redirectionListener != null) {
                redirectionListener.onRedirectionRequest(cmd);
            }
        }

        @Override
        public void cleanup() {
            if(redirect != null) {
                redirect.beforeObjects.clear();
                redirect.afterObjects.clear();

                if(redirectionListener != null) {
                    redirectionListener.onRedirectionEnd(redirect);
                }

                redirect = null;
            }
        }
    };
    private OnRedirectionListener redirectionListener;
    public void setRedirectionListener(OnRedirectionListener redirectionListener) {
        this.redirectionListener = redirectionListener;
    }

    private final String COMMANDS_PKG = "ohi.andre.consolelauncher.commands.main.raw";

    private CmdTrigger[] triggers = new CmdTrigger[]{
            new AliasTrigger(),
            new TuiCommandTrigger(),
            new AppTrigger(),
//            keep this as last trigger
            new SystemCommandTrigger()
    };
    private MainPack mainPack;
    private ShellManager shell;

    private Context mContext;

    private Inputable in;
    private Outputable out;

    private boolean showAliasValue;
    private boolean showAppHistory;

    protected MainManager(LauncherActivity c, Inputable i, Outputable o, Suggester sugg) {
        mContext = c;

        in = i;
        out = o;

        showAliasValue = XMLPrefsManager.get(boolean.class, XMLPrefsManager.Behavior.show_alias_content);
        showAppHistory = XMLPrefsManager.get(boolean.class, XMLPrefsManager.Behavior.show_launch_history);

        CommandGroup group = new CommandGroup(mContext, COMMANDS_PKG);

        ContactManager cont = null;
        try {
            cont = new ContactManager(mContext);
        } catch (NullPointerException e) {}

        CommandExecuter executer = new CommandExecuter() {
            @Override
            public String exec(String input, String alias) {
                onCommand(input, alias);
                return null;
            }
        };

        MusicManager music = new MusicManager(mContext, out);

        AppsManager appsMgr = new AppsManager(c, out, sugg);
        AliasManager aliasManager = new AliasManager();

        shell = new ShellManager(out);

        mainPack = new MainPack(mContext, group, aliasManager, appsMgr, music, cont, c, executer, out, redirectator, shell);
    }

//    command manager
    public void onCommand(String input, String alias) {

        input = Tuils.removeUnncesarySpaces(input);

        if(redirect != null) {
            if(!redirect.isWaitingPermission()) {
                redirect.afterObjects.add(input);
            }
            String output = redirect.onRedirect(mainPack);
            out.onOutput(output);

            return;
        }

        if(alias != null && showAliasValue) {
            out.onOutput(alias + " --> " + "[" + input + "]");
        }

        for (CmdTrigger trigger : triggers) {
            boolean r;
            try {
                r = trigger.trigger(mainPack, input);
            } catch (Exception e) {
                out.onOutput(Tuils.getStackTrace(e));
                return;
            }
            if (r) {
                return;
            } else {}
        }
    }

    public void onLongBack() {
        in.in(Tuils.EMPTYSTRING);
    }

    public void sendPermissionNotGrantedWarning() {
        redirectator.cleanup();
    }

    public void dispose() {
        mainPack.dispose();
    }

    public void destroy() {
        mainPack.destroy();
    }

    public MainPack getMainPack() {
        return mainPack;
    }

    interface CmdTrigger {
        boolean trigger(ExecutePack info, String input) throws Exception;
    }

    private class AliasTrigger implements CmdTrigger {
        @Override
        public boolean trigger(ExecutePack info, String alias) {
            String aliasValue = mainPack.aliasManager.getAlias(alias);
            if (aliasValue == null) {
                return false;
            }

            mainPack.executer.exec(aliasValue, alias);

            return true;
        }
    }

    private class SystemCommandTrigger implements CmdTrigger {

        @Override
        public boolean trigger(final ExecutePack info, final String input) throws Exception {
            new Thread() {
                @Override
                public void run() {
                    shell.cmd(input, true);
                    ((MainPack) info).currentDirectory = shell.currentDir();
                };
            }.start();

            return true;
        }
    }

    private class AppTrigger implements CmdTrigger {

        String appFormat;
        int timeColor;
        int outputColor;

        Pattern pa = Pattern.compile("%a", Pattern.CASE_INSENSITIVE);
        Pattern pp = Pattern.compile("%p", Pattern.CASE_INSENSITIVE);
        Pattern pl = Pattern.compile("%l", Pattern.CASE_INSENSITIVE);
        Pattern pn = Pattern.compile("%n", Pattern.CASE_INSENSITIVE);

        @Override
        public boolean trigger(ExecutePack info, String input) {
            AppsManager.LaunchInfo i = mainPack.appsManager.findLaunchInfoWithLabel(input, AppsManager.SHOWN_APPS);
            if (i == null) {
                return false;
            }

            Intent intent = mainPack.appsManager.getIntent(i);
            if (intent == null) {
                return false;
            }

            if(showAppHistory) {
                if(appFormat == null) {
                    appFormat = XMLPrefsManager.get(String.class, XMLPrefsManager.Behavior.app_launch_format);
                    timeColor = XMLPrefsManager.getColor(XMLPrefsManager.Theme.time_color);
                    outputColor = XMLPrefsManager.getColor(XMLPrefsManager.Theme.output_color);
                }

                String a = new String(appFormat);
                a = pa.matcher(a).replaceAll(intent.getComponent().getClassName());
                a = pp.matcher(a).replaceAll(intent.getComponent().getPackageName());
                a = pl.matcher(a).replaceAll(i.publicLabel);
                a = pn.matcher(a).replaceAll(Tuils.NEWLINE);

                SpannableString text = new SpannableString(a);
                text.setSpan(new ForegroundColorSpan(outputColor), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                CharSequence s = TimeManager.replace(text, timeColor);

                out.onOutput(s, TerminalManager.CATEGORY_GENERAL);
            }

            mContext.startActivity(intent);
            return true;
        }
    }

    private class TuiCommandTrigger implements CmdTrigger {

        @Override
        public boolean trigger(final ExecutePack info, final String input) throws Exception {

            final boolean[] returnValue = new boolean[1];

            new StoppableThread() {
                @Override
                public void run() {
                    super.run();

                    mainPack.lastCommand = input;

                    try {
                        Command command = CommandTuils.parse(input, info, false);

                        synchronized (returnValue) {
                            returnValue[0] = command != null;
                            returnValue.notify();
                        }

                        if (command != null) {
                            String output = command.exec(mContext.getResources(), info);

                            if(output != null) {
                                out.onOutput(output);
                            }
                        }
                    } catch (Exception e) {
                        out.onOutput(e.toString());
                        Log.e("andre", "", e);
                    }
                }
            }.start();

            synchronized (returnValue) {
                returnValue.wait();
                return returnValue[0];
            }
        }
    }
}
