package ohi.andre.consolelauncher.managers;

import android.content.Context;
import android.graphics.Typeface;
import android.os.IBinder;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ohi.andre.consolelauncher.UIManager;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.commands.main.raw.clear;
import ohi.andre.consolelauncher.tuils.TimeManager;
import ohi.andre.consolelauncher.tuils.Tuils;

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

public class TerminalManager {

    private final int SCROLL_DELAY = 200;
    private final int CMD_LIST_SIZE = 40;

    public static final int CATEGORY_INPUT = 10;
    public static final int CATEGORY_OUTPUT = 11;
    public static final int CATEGORY_NOTIFICATION = 12;
    public static final int CATEGORY_GENERAL = 13;

    private long lastEnter;

    private String prefix;
    private String suPrefix;

    private ScrollView mScrollView;
    private TextView mTerminalView;
    private EditText mInputView;

    private List<String> cmdList = new ArrayList<>(CMD_LIST_SIZE);
    private int howBack = -1;

    private Runnable mScrollRunnable = new Runnable() {
        @Override
        public void run() {
            mScrollView.fullScroll(ScrollView.FOCUS_DOWN);
            mInputView.requestFocus();
        }
    };

    private SkinManager mSkinManager;

    private UIManager.OnNewInputListener mInputListener;
    private UIManager.SuggestionNavigator mSuggestionNavigator;

    private List<Messager> messagers = new ArrayList<>();

    private MainPack mainPack;

    private boolean defaultHint = true;

    private int clearCmdsCount= 0, messagesCmdsCount = 0;

    private int clearAfterCmds, clearAfterMs, maxLines;
    private Runnable clearRunnable = new Runnable() {

        @Override
        public void run() {
            clear();
            mTerminalView.postDelayed(this, clearAfterMs);
        }
    };

    private String inputFormat;
    private String outputFormat;

    public TerminalManager(final TextView terminalView, EditText inputView, TextView prefixView, ImageButton submitView, final ImageButton backView, ImageButton nextView, ImageButton deleteView,
                           ImageButton pasteView, SkinManager skinManager, final Context context, MainPack mainPack) {
        if (terminalView == null || inputView == null || prefixView == null || skinManager == null)
            throw new UnsupportedOperationException();

        final Typeface lucidaConsole = Typeface.createFromAsset(context.getAssets(), "lucida_console.ttf");

        this.mSkinManager = skinManager;
        this.mainPack = mainPack;

        this.clearAfterMs = XMLPrefsManager.get(int.class, XMLPrefsManager.Behavior.clear_after_seconds) * 1000;
        this.clearAfterCmds = XMLPrefsManager.get(int.class, XMLPrefsManager.Behavior.clear_after_cmds);
        this.maxLines = XMLPrefsManager.get(int.class, XMLPrefsManager.Behavior.max_lines);

        inputFormat = XMLPrefsManager.get(String.class, XMLPrefsManager.Behavior.input_format);
        outputFormat = XMLPrefsManager.get(String.class, XMLPrefsManager.Behavior.output_format);

        prefix = skinManager.prefix;
        suPrefix = XMLPrefsManager.get(String.class, XMLPrefsManager.Ui.input_root_prefix);

        prefixView.setTypeface(skinManager.systemFont ? Typeface.DEFAULT : lucidaConsole);
        prefixView.setTextColor(this.mSkinManager.inputColor);
        prefixView.setTextSize(this.mSkinManager.getTextSize());
        prefixView.setText(prefix.endsWith(Tuils.SPACE) ? prefix : prefix + Tuils.SPACE);

        if (submitView != null) {
            submitView.setColorFilter(mSkinManager.enter_color);
            submitView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onNewInput();
                }
            });
        }

        if (backView != null) {
            ((View) backView.getParent()).setBackgroundColor(mSkinManager.toolbarBg);
            backView.setColorFilter(this.mSkinManager.toolbarColor);
            backView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBackPressed();
                }
            });
        }

        if (nextView != null) {
            nextView.setColorFilter(this.mSkinManager.toolbarColor);
            nextView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onNextPressed();
                }
            });
        }

        if (pasteView != null) {
            pasteView.setColorFilter(this.mSkinManager.toolbarColor);
            pasteView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String text = Tuils.getTextFromClipboard(context);
                    if(text != null && text.length() > 0) {
                        setInput(getInput() + text);
                    }
                }
            });
        }

        if (deleteView != null) {
            deleteView.setColorFilter(this.mSkinManager.toolbarColor);
            deleteView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setInput(Tuils.EMPTYSTRING);
                }
            });
        }

        this.mTerminalView = terminalView;
        this.mTerminalView.setTypeface(skinManager.systemFont ? Typeface.DEFAULT : lucidaConsole);
        this.mTerminalView.setTextSize(mSkinManager.getTextSize());
        this.mTerminalView.setFocusable(false);
        setupScroller();

        if(clearAfterMs > 0) this.mTerminalView.postDelayed(clearRunnable, clearAfterMs);
        if(maxLines > 0) {
            this.mTerminalView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if(TerminalManager.this.mTerminalView == null) return true;

                    Layout l = terminalView.getLayout();
                    if(l == null) return true;

                    int count = l.getLineCount() - 1;

                    if(count > maxLines) {
                        int excessive = count - maxLines;

                        CharSequence text = terminalView.getText();
                        while (excessive >= 0) {
                            int index = TextUtils.indexOf(text, Tuils.NEWLINE);
                            if(index == -1) break;
                            text = text.subSequence(index + 1, text.length());
                            excessive--;
                        }

                        terminalView.setText(text);
                    }

                    return true;
                }
            });
        }

        View v = mTerminalView;
        do {
            v = (View) v.getParent();
        } while (!(v instanceof ScrollView));
        this.mScrollView = (ScrollView) v;

        this.mInputView = inputView;
        this.mInputView.setTextSize(mSkinManager.getTextSize());
        this.mInputView.setTextColor(mSkinManager.inputColor);
        this.mInputView.setTypeface(skinManager.systemFont ? Typeface.DEFAULT : lucidaConsole);
        this.mInputView.setHint(Tuils.getHint(skinManager, mainPack.currentDirectory.getAbsolutePath()));
        this.mInputView.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        this.mInputView.setOnEditorActionListener(new TextView.OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if(!mInputView.hasFocus()) mInputView.requestFocus();

//                physical enter
                if(actionId == KeyEvent.ACTION_DOWN) {
                    if(lastEnter == 0) {
                        lastEnter = System.currentTimeMillis();
                    } else {
                        long difference = System.currentTimeMillis() - lastEnter;
                        lastEnter = System.currentTimeMillis();
                        if(difference < 350) {
                            return true;
                        }
                    }
                }

                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || actionId == KeyEvent.ACTION_DOWN) {
                    onNewInput();
                }

//                if(event == null && actionId == EditorInfo.IME_NULL) onNewInput();
//                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) onNewInput();

                return true;
            }
        });
    }

    public void addMessager(Messager messager) {
        messagers.add(messager);
    }

    private void setupNewInput() {
        mInputView.setText(Tuils.EMPTYSTRING);

        if(defaultHint) {
            mInputView.setHint(Tuils.getHint(mSkinManager, mainPack.currentDirectory.getAbsolutePath()));
        }

        requestInputFocus();
    }

    private boolean onNewInput() {
        if (mInputView == null) {
            return false;
        }

        String input = mInputView.getText().toString().trim();

        if(input.length() > 0) {
            clearCmdsCount++;
            messagesCmdsCount++;

            if(clearCmdsCount != 0 && clearAfterCmds > 0 && clearCmdsCount % clearAfterCmds == 0) clear();

            for (Messager messager : messagers) if (messagesCmdsCount != 0 && messagesCmdsCount % messager.n == 0) writeToView(messager.message, CATEGORY_OUTPUT);

            writeToView(input, CATEGORY_INPUT);

            if(cmdList.size() == CMD_LIST_SIZE) {
                cmdList.remove(0);
            }
            cmdList.add(cmdList.size(), input);
            howBack = -1;
        }


        if (mInputListener != null) {
            mInputListener.onNewInput(input);
        }

        setupNewInput();

        return true;
    }

    public void setOutput(CharSequence output, int type) {
        if (output == null || output.length() == 0) return;

        if(output.equals(clear.CLEAR)) {
            clear();
            return;
        }

        writeToView(output, type);
    }

    public void onBackPressed() {
        if(cmdList.size() > 0) {

            if(howBack == -1) {
                howBack = cmdList.size();
            } else if(howBack == 0) {
                return;
            }
            howBack--;

            setInput(cmdList.get(howBack));
        }
    }

    public void onNextPressed() {
        if(howBack != -1 && howBack < cmdList.size()) {
            howBack++;

            String input;
            if(howBack == cmdList.size()) {
                input = Tuils.EMPTYSTRING;
            } else {
                input = cmdList.get(howBack);
            }

            setInput(input);
        }
    }

    final String FORMAT_INPUT = "%i";
    final String FORMAT_OUTPUT = "%o";
    final String FORMAT_PREFIX = "%p";
    final String FORMAT_NEWLINE = "%n";

    private void writeToView(final CharSequence text, final int type) {
        mTerminalView.post(new Runnable() {
            @Override
            public void run() {

                CharSequence s = getFinalText(text, type);
                mTerminalView.append(TextUtils.concat(Tuils.NEWLINE, s));
                scrollToEnd();
            }
        });
    }

    private CharSequence getFinalText(CharSequence t, int type) {

        CharSequence s;
        switch (type) {
            case CATEGORY_INPUT:
                t = t.toString().trim();

                boolean su = t.toString().startsWith("su ");

                SpannableString si = new SpannableString(inputFormat);
                si.setSpan(new ForegroundColorSpan(mSkinManager.inputColor), 0, inputFormat.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                s = TimeManager.replace(si, mSkinManager.time_color);
                s = TextUtils.replace(s,
                        new String[] {FORMAT_INPUT, FORMAT_PREFIX, FORMAT_NEWLINE,
                                FORMAT_INPUT.toUpperCase(), FORMAT_PREFIX.toUpperCase(), FORMAT_NEWLINE.toUpperCase()},
                        new CharSequence[] {t, su ? suPrefix : prefix, Tuils.NEWLINE, t, su ? suPrefix : prefix, Tuils.NEWLINE});

                break;
            case CATEGORY_OUTPUT:
                t = t.toString().trim();

                SpannableString so = new SpannableString(outputFormat);
                so.setSpan(new ForegroundColorSpan(mSkinManager.outputColor), 0, outputFormat.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                s = TextUtils.replace(so,
                        new String[] {FORMAT_OUTPUT, FORMAT_NEWLINE, FORMAT_OUTPUT.toUpperCase(), FORMAT_NEWLINE.toUpperCase()},
                        new CharSequence[] {t, Tuils.NEWLINE, t, Tuils.NEWLINE});

                break;
            case CATEGORY_NOTIFICATION: case CATEGORY_GENERAL:
                s = t;
                break;
            default:
                return null;
        }

        return s;
    }

    public void simulateEnter() {
        onNewInput();
    }

    public void setupScroller() {
        this.mTerminalView.setMovementMethod(new ScrollingMovementMethod());
    }

    public String getInput() {
        return mInputView.getText().toString();
    }

    public void setInput(String input) {
        mInputView.setText(input);
        focusInputEnd();
    }

    public void setHint(String hint) {
        defaultHint = false;

        if(mInputView != null) {
            mInputView.setHint(hint);
        }
    }

    public void setDefaultHint() {
        defaultHint = true;

        if(mInputView != null) {
            mInputView.setHint(Tuils.getHint(mSkinManager, mainPack.currentDirectory.getAbsolutePath()));
        }
    }

    public void setInputListener(UIManager.OnNewInputListener listener) {
        this.mInputListener = listener;
    }

    public void setSuggestionNavigator(UIManager.SuggestionNavigator navigator) {
        this.mSuggestionNavigator = navigator;
    }

    public void focusInputEnd() {
        mInputView.setSelection(getInput().length());
    }

    public void scrollToEnd() {
        mScrollView.postDelayed(mScrollRunnable, SCROLL_DELAY);
    }

    public void requestInputFocus() {
        mInputView.requestFocus();
    }

    public IBinder getInputWindowToken() {
        return mInputView.getWindowToken();
    }

    public View getInputView() {
        return mInputView;
    }

    public void clear() {
        mTerminalView.post(new Runnable() {
            @Override
            public void run() {
                mTerminalView.setText(Tuils.EMPTYSTRING);
            }
        });
        cmdList.clear();
        clearCmdsCount = 0;
    }

    public static class Messager {

        int n;
        String message;

        public Messager(int n, String message) {
            this.n = n;
            this.message = message;
        }
    }

}
