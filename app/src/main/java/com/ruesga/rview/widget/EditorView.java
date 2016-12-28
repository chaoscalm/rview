/*
 * Copyright (C) 2016 Jorge Ruesga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ruesga.rview.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.Pair;
import android.util.SparseArray;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.ruesga.rview.R;

import java.util.Random;
import java.util.regex.Pattern;

public class EditorView extends FrameLayout {

    public interface OnReadContentReadyListener {
        void onReadContentReady(byte[] content);
        void onContentUnchanged();
    }

    public interface OnContentChangedListener {
        void onContentChanged();
    }

    private final static int MAX_EXCHANGE_SIZE = 25000;

    private WebView mWebView;
    private final Random mRandom = new Random();

    private final Pattern mPattern = Pattern.compile("edt:(\\d)+:[pf]:");

    private boolean mReadOnly = false;
    private boolean mWrap = false;
    private int mTextSize = 14;
    private boolean mIsDirty;
    private boolean mInitialLoad = true;

    private boolean mReady;
    private boolean mIgnoreNextUnsavedEvent;
    private String mPendingFileName;
    private byte[] mPendingContent;

    private OnContentChangedListener mContentChangedListener;

    private SparseArray<Pair<StringBuilder, OnReadContentReadyListener>> mMap = new SparseArray<>();

    public EditorView(Context context) {
        this(context, null);
    }

    public EditorView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EditorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        Resources.Theme theme = context.getTheme();
        TypedArray a = theme.obtainStyledAttributes(
                attrs, R.styleable.EditorView, defStyleAttr, 0);
        int n = a.getIndexCount();
        for (int i = 0; i < n; i++) {
            int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.EditorView_locked:
                    mReadOnly = a.getBoolean(attr, mReadOnly);
                    break;
                case R.styleable.EditorView_wrap:
                    mWrap = a.getBoolean(attr, mWrap);
                    break;
            }
        }
        a.recycle();

        // Create the webview
        mWebView = createWebView();
        addView(mWebView);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView webview = new WebView(getContext());
        webview.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webview.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Editor is ready. Send configuration and content
                mWebView.loadUrl("javascript: setReadOnly(" + mReadOnly + ");");
                mWebView.loadUrl("javascript: setWrapMode(" + mWrap + ");");
                mWebView.loadUrl("javascript: setTextSize(" + mTextSize + ");");
                mReady = true;

                if (mPendingContent != null) {
                    loadEncodedContent(mPendingFileName, mPendingContent);
                    mPendingFileName = null;
                    mPendingContent = null;
                }
            }
        });
        webview.setWebChromeClient(new WebChromeClient() {
            @Override
            @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                final String msg = consoleMessage.message();
                if (msg.equals("edt:u")) {
                    if (!mIgnoreNextUnsavedEvent) {
                        mIsDirty = true;
                        mContentChangedListener.onContentChanged();
                    }
                    mIgnoreNextUnsavedEvent = false;
                }

                if (mPattern.matcher(msg).find()) {
                    String[] v = msg.split(":");
                    int key = Integer.parseInt(v[1]);
                    final Pair<StringBuilder, OnReadContentReadyListener> o = mMap.get(key);
                    if (o == null) {
                        super.onConsoleMessage(consoleMessage);
                        return false;
                    }

                    // Read the message
                    switch (v[2]) {
                        case "p":
                            // partial
                            o.first.append(v[3]);
                            break;
                        default:
                            // finish
                            byte[] content;
                            if (o.first.length() == 0) {
                                content = new byte[0];
                            } else {
                                content = o.first.toString().getBytes();
                            }
                            o.second.onReadContentReady(content);
                            mMap.remove(key);
                            break;
                    }
                    return true;
                }

                super.onConsoleMessage(consoleMessage);
                return false;
            }
        });
        WebSettings webSettings = webview.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webview.loadUrl("file:///android_asset/editor/editor.html");
        return webview;
    }

    public boolean isWrap() {
        return mWrap;
    }

    public EditorView setWrap(boolean wrap) {
        mWrap = wrap;
        if (mReady) {
            mWebView.loadUrl("javascript: setWrapMode(" + mWrap + ");");
        }
        return this;
    }

    public boolean isReadOnly() {
        return mReadOnly;
    }

    public EditorView setReadOnly(boolean readOnly) {
        mReadOnly = readOnly;
        if (mReady) {
            mWebView.loadUrl("javascript: setReadOnly(" + mReadOnly + ");");
        }
        return this;
    }

    public int getTextSize() {
        return mTextSize;
    }

    public EditorView setTextSize(int textSize) {
        mTextSize = textSize;
        if (mReady) {
            mWebView.loadUrl("javascript: setTextSize(" + mTextSize + ");");
        }
        return this;
    }

    public boolean isDirty() {
        return mIsDirty;
    }

    public EditorView listenOn(OnContentChangedListener cb) {
        mContentChangedListener = cb;
        return this;
    }

    public void loadContent(String fileName, byte[] content) {
        loadEncodedContent(fileName, Base64.encode(content, Base64.NO_WRAP));
    }

    public void loadEncodedContent(String fileName, byte[] encoded) {
        // Enqueue the request
        if (!mReady) {
            mPendingFileName = fileName;
            mPendingContent = encoded;
            return;
        }

        if (encoded.length > 0) {
            int s = 0;
            do {
                int e = Math.min(encoded.length, s + MAX_EXCHANGE_SIZE);
                String msg = new String(encoded, s, e);
                mWebView.loadUrl("javascript: addPartialContent('" + msg + "');");

                if (e <= MAX_EXCHANGE_SIZE) {
                    break;
                }
                s = e;
            } while (true);
        }
        mIgnoreNextUnsavedEvent = true;
        if (!mInitialLoad) {
            mIsDirty = false;
        }
        mInitialLoad = false;
        mWebView.loadUrl("javascript: loadContent('" + fileName + "');");
    }

    public void readContent(OnReadContentReadyListener cb) {
        if (mIsDirty) {
            int key = mRandom.nextInt(Short.MAX_VALUE);
            mMap.put(key, new Pair<>(new StringBuilder(), cb));
            mWebView.loadUrl("javascript: readContent(" + key + ");");
        } else {
            cb.onContentUnchanged();
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        SavedState savedState = new SavedState(super.onSaveInstanceState());
        savedState.mReadOnly = mReadOnly;
        savedState.mWrap = mWrap;
        savedState.mTextSize = mTextSize;
        savedState.mIsDirty = mIsDirty;
        return savedState;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        //begin boilerplate code so parent classes can restore state
        if(!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState savedState = (SavedState) state;
        super.onRestoreInstanceState(savedState.getSuperState());

        mReadOnly = savedState.mReadOnly;
        mWrap = savedState.mWrap;
        mTextSize = savedState.mTextSize;
        mIsDirty = savedState.mIsDirty;
    }

    static class SavedState extends BaseSavedState {
        boolean mReadOnly;
        boolean mWrap;
        int mTextSize;
        boolean mIsDirty;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            mReadOnly = in.readInt() == 1;
            mWrap = in.readInt() == 1;
            mTextSize = in.readInt();
            mIsDirty = in.readInt() == 1;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(mReadOnly ? 1 : 0);
            out.writeInt(mWrap ? 1 : 0);
            out.writeInt(mTextSize);
            out.writeInt(mIsDirty ? 1 : 0);
        }

        //required field that makes Parcelables from a Parcel
        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }
}
