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
package com.ruesga.rview.aceeditor;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ClientCertRequest;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.NestedScrollingChild;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.ViewCompat;

/**
 * This is a {@link WebView} subclass to deal with IME issues with the Ace Code Editor library. See
 * bellow for a detailed of bug threads describing the problem. Currently version is mostly working
 * on most of the keyboard devices, but there could be some of them with bad behaviours because of
 * the described bug. This implementation disables the IME input method of the {@link WebView}.
 * <p>
 * It also provides a @{link NestedScrollingChild} implementation to deal with
 * {@link CoordinatorLayout}.
 * <p>
 * It also provides a fake implementation of {@link ActionMode} to deal with the wrong
 * selection mode of the Ace component. For now, in only provides support for KITKAT and up,
 * since we need a WebView's Chromium implementation.
 *
 * @see "https://bugs.chromium.org/p/chromium/issues/detail?id=118639"
 * @see "https://github.com/ajaxorg/ace/issues/2964"
 * @see "https://github.com/ajaxorg/ace/issues/1917"
 * @see "https://github.com/takahirom/webview-in-coordinatorlayout"
 */
class AceWebView extends WebView implements NestedScrollingChild {
    private int[] mLastTouch = new int[2];
    private int mLastY;
    private long mLastTouchDownEvent;
    private boolean mHasMoveEvent;
    private final int[] mScrollOffset = new int[2];
    private final int[] mScrollConsumed = new int[2];
    private int mNestedOffsetY;
    private final int mActionModeOffsetLeft;
    private NestedScrollingChildHelper mChildHelper;
    private OnLongClickListener mWrappedLongClickListener = null;
    private final Handler mHandler;

    private WebChromeClient mWrappedWebChromeClient = new WebChromeClient();
    private WebViewClient mWrappedWebViewClient = new WebViewClient();

    private AceSelectionActionModeHelper mSelectionHelper;
    private final DisplayMetrics mMetrics;
    private final ClipboardManager mClipboard;

    private boolean mReadOnly;
    private boolean mHasPaste;

    private static class Selection {
        final boolean selected;
        final int x1;
        final int y1;
        final int x2;
        final int y2;

        private Selection(boolean selected, int x1, int y1, int x2, int y2) {
            this.selected = selected;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    private static final int SELECTION_CHANGED_MESSAGE = 0;
    private Selection mLastSelection;
    private boolean mSelectionEvent;

    public AceWebView(Context context) {
        super(context);
        mMetrics = context.getResources().getDisplayMetrics();
        mActionModeOffsetLeft = (int) (64 * mMetrics.density);

        mClipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        mChildHelper = new NestedScrollingChildHelper(this);
        mHandler = new Handler(msg -> {
            switch (msg.what) {
                case SELECTION_CHANGED_MESSAGE:
                    if (mSelectionHelper != null) {
                        mSelectionEvent = true;
                        Rect r = new Rect();
                        getWindowVisibleDisplayFrame(r);
                        mLastSelection = (Selection) msg.obj;
                        boolean xVisible = (mLastSelection.x1 > 0 || mLastSelection.x2 > 0)
                                && (mLastSelection.x1 < r.width()
                                || mLastSelection.x2 < r.width());
                        boolean yVisible = (mLastSelection.y1 > 0 || mLastSelection.y2 > 0)
                                && (mLastSelection.y1 < r.height()
                                || mLastSelection.y2 < r.height());
                        mSelectionHelper.hasSelection(mLastSelection.selected);
                        if ((mLastSelection.selected || mHasPaste) && xVisible && yVisible) {
                            mSelectionHelper.show(this,
                                    Math.max(0, mLastSelection.x1 - mActionModeOffsetLeft),
                                    mLastSelection.y1);
                        } else {
                            mSelectionHelper.dismiss();
                        }
                    }
                    break;
            }
            return false;
        });

        setNestedScrollingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            configureActionMode();
        }
        super.setWebChromeClient(mWebChromeClient);
        super.setWebViewClient(mWebViewClient);
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void configureActionMode() {
        setClickable(false);
        setLongClickable(false);
        setHapticFeedbackEnabled(false);

        mSelectionHelper = new AceSelectionActionModeHelper(getContext());
        mSelectionHelper.listenOn((itemId, label) -> {
            switch (itemId) {
                case AceSelectionActionModeHelper.OPTION_CUT:
                    // On old Android versions, cut doesn't work. Just use our own implementation.
                    loadUrl("javascript: ace_copy(true);");
                    requestFocus();
                    mHasPaste = true;
                    break;
                case AceSelectionActionModeHelper.OPTION_COPY:
                    // On old Android versions, copy doesn't work. Just use our own implementation.
                    loadUrl("javascript: ace_copy(false);");
                    requestFocus();
                    mHasPaste = true;
                    break;
                case AceSelectionActionModeHelper.OPTION_PASTE:
                    // On old Android versions, paste doesn't work. Just use our own implementation.
                    String text = "";
                    if (mClipboard.hasPrimaryClip()
                            && mClipboard.getPrimaryClip() != null
                            && mClipboard.getPrimaryClip().getItemCount() > 0
                            && mClipboard.getPrimaryClipDescription() != null
                            && mClipboard.getPrimaryClipDescription().hasMimeType("text/*")) {
                        text = mClipboard.getPrimaryClip().getItemAt(0).getText().toString();
                    }
                    final String s = new String(Base64.encode(text.getBytes(), Base64.NO_WRAP));
                    loadUrl("javascript: ace_paste('" + s + "');");
                    requestFocus();
                    mHasPaste = false;

                    // Clear clipboard
                    ClipData clipData = ClipData.newPlainText("", "");
                    mClipboard.setPrimaryClip(clipData);
                    break;
                case AceSelectionActionModeHelper.OPTION_SELECT_ALL:
                    // On old Android versions, select all doesn't work. Just use our
                    // own implementation.
                    loadUrl("javascript: ace_select_all();");
                    return false;
                default:
                    loadUrl("javascript: ace_get_selected_text('" + itemId + "');");
                    break;
            }
            return true;
        });

        super.setOnClickListener(v -> {});
        super.setOnLongClickListener(v -> {
            // Show keyboard and ensure the editor has the focus
            showKeyboard();
            loadUrl("javascript: ace_request_focus();");
            mHandler.postDelayed(() -> {
                // Select the current word
                loadUrl("javascript: ace_select_word();");
                requestFocus();
            }, 50L);

            // When editor is empty, selection events never fired, so we just ensure
            // an event using the last clicked coordinates.
            mSelectionEvent = false;
            mHandler.postDelayed(() -> {
                if (!mSelectionEvent) {
                    showActionModeAtLastLocation();
                }
            }, 450L);

            if (mWrappedLongClickListener != null) {
                mWrappedLongClickListener.onLongClick(v);
            }
            return true;
        });
    }

    void setReadOnly(boolean readOnly) {
        mReadOnly = readOnly;
        if (mSelectionHelper != null) {
            mSelectionHelper.setReadOnly(readOnly);
        }
    }

    @Override
    public void setWebViewClient(WebViewClient client) {
        mWrappedWebViewClient = client;
    }

    @Override
    public void setWebChromeClient(WebChromeClient client) {
        mWrappedWebChromeClient = client;
    }

    @Override
    public final void setOnLongClickListener(@Nullable OnLongClickListener l) {
        mWrappedLongClickListener = l;
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            return null;
        }
        return super.startActionMode(callback);


    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback, int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            return null;
        }
        return super.startActionMode(callback, type);
    }

    private boolean isKeyboardVisible() {
        Rect r = new Rect();
        getWindowVisibleDisplayFrame(r);
        return (getRootView().getHeight() - (r.height()) / mMetrics.density) > 200;
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        if (mSelectionHelper != null && mSelectionHelper.isShowing()
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            mHasPaste = true;
            mSelectionHelper.dismiss();
            return !isKeyboardVisible();
        }
        return super.dispatchKeyEventPreIme(event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection inputConnection = super.onCreateInputConnection(outAttrs);
        outAttrs.imeOptions = EditorInfo.IME_NULL;
        outAttrs.inputType = InputType.TYPE_NULL;
        return inputConnection;
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent ev) {
        boolean returnValue = false;

        MotionEvent event = MotionEvent.obtain(ev);
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mNestedOffsetY = 0;
        }
        mLastTouch[0] = (int) event.getX();
        mLastTouch[1] = (int) event.getY();
        event.offsetLocation(0, mNestedOffsetY);
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                int deltaY = mLastY - mLastTouch[1];
                if (dispatchNestedPreScroll(0, deltaY, mScrollConsumed, mScrollOffset)) {
                    deltaY -= mScrollConsumed[1];
                    mLastY = mLastTouch[1] - mScrollOffset[1];
                    event.offsetLocation(0, -mScrollOffset[1]);
                    mNestedOffsetY += mScrollOffset[1];
                }
                returnValue = super.onTouchEvent(event);

                if (dispatchNestedScroll(0, mScrollOffset[1], 0, deltaY, mScrollOffset)) {
                    event.offsetLocation(0, mScrollOffset[1]);
                    mNestedOffsetY += mScrollOffset[1];
                    mLastY -= mScrollOffset[1];
                }
                mHasMoveEvent = true;
                break;
            case MotionEvent.ACTION_DOWN:
                returnValue = super.onTouchEvent(event);
                mLastY = mLastTouch[1];
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL);
                mLastTouchDownEvent = System.currentTimeMillis();
                mHasMoveEvent = false;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                long delta = (System.currentTimeMillis() - mLastTouchDownEvent);
                returnValue = super.onTouchEvent(event);
                stopNestedScroll();
                if (mSelectionHelper != null &&
                        !mHasMoveEvent && delta < ViewConfiguration.getTapTimeout()) {
                    if (mSelectionHelper.isShowing()) {
                        mSelectionHelper.dismiss();
                    }
                }

                // Force Keyboard
                if (!mHasMoveEvent && action == MotionEvent.ACTION_UP) {
                    showKeyboard();

                    if (mHasPaste) {
                        mSelectionEvent = false;
                        mHandler.postDelayed(() -> {
                            if (!mSelectionEvent) {
                                showActionModeAtLastLocation();
                            }
                        }, 450L);
                    }
                }
                break;
        }
        return returnValue;
    }

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        mChildHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public boolean isNestedScrollingEnabled() {
        return mChildHelper.isNestedScrollingEnabled();
    }

    @Override
    public boolean startNestedScroll(int axes) {
        return mChildHelper.startNestedScroll(axes);
    }

    @Override
    public void stopNestedScroll() {
        mChildHelper.stopNestedScroll();
    }

    @Override
    public boolean hasNestedScrollingParent() {
        return mChildHelper.hasNestedScrollingParent();
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed,
            int dyUnconsumed, int[] offsetInWindow) {
        return mChildHelper.dispatchNestedScroll(
                dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed, int[] offsetInWindow) {
        return mChildHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return mChildHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return mChildHelper.dispatchNestedPreFling(velocityX, velocityY);
    }

    private void showKeyboard() {
        if (!mReadOnly) {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    private void showActionModeAtLastLocation() {
        int[] xy = new int[2];
        getLocationOnScreen(xy);
        mSelectionHelper.show(this, xy[0] + mLastTouch[0] - mActionModeOffsetLeft,
                xy[1] + mLastTouch[1]);
    }

    private void filterAceMessage(String msg) {
        if (msg.startsWith("edt:s:")) {
            if (mSelectionHelper == null) {
                return;
            }
            String[] v = msg.replaceFirst("edt:s:", "").split(";");
            boolean selected = Boolean.valueOf(v[0]);
            int[] location = new int[2];
            getLocationInWindow(location);
            int screenX = Float.valueOf(v[1]).intValue();
            int screenY = Float.valueOf(v[2]).intValue();

            float padding = (mMetrics.density * 0.2f) / 3.5f;
            int x1 = Float.valueOf(v[3]).intValue();
            int y1 = Float.valueOf(v[4]).intValue()
                    + (int)(Float.valueOf(v[7]).intValue() * padding);
            int x2 = Float.valueOf(v[5]).intValue();
            int y2 = Float.valueOf(v[6]).intValue()
                    + (int)(Float.valueOf(v[9]).intValue() * padding);

            x1 = (getRootView().getWidth() * x1 / screenX) + location[0];
            y1 = (getRootView().getHeight() * y1 / screenY) + location[1]
                    - mSelectionHelper.getDefaultHeight();
            x2 = (getRootView().getWidth() * x2 / screenX) + location[0];
            y2 = (getRootView().getHeight() * y2 / screenY) + location[1]
                    - mSelectionHelper.getDefaultHeight();

            mHandler.removeMessages(SELECTION_CHANGED_MESSAGE);
            Message message = Message.obtain(mHandler);
            message.what = SELECTION_CHANGED_MESSAGE;
            message.obj = new Selection(selected, x1, y1, x2, y2);
            message.sendToTarget();
        } else if (msg.startsWith("edt:copy:")) {
            String s = msg.replaceFirst("edt:copy:", "");
            if (!TextUtils.isEmpty(s)) {
                s = new String(Base64.decode(s, Base64.NO_WRAP));
                ClipData clip = ClipData.newPlainText(getClass().getSimpleName(), s);
                mClipboard.setPrimaryClip(clip);
            }
        } else if (msg.startsWith("edt:seltext:")) {
            if (mSelectionHelper == null) {
                return;
            }

            String s = msg.replaceFirst("edt:seltext:", "");
            int action = Integer.valueOf(s.substring(0, s.indexOf(':')));
            s = s.substring(s.indexOf(':') + 1);
            if (!TextUtils.isEmpty(s)) {
                s = new String(Base64.decode(s, Base64.NO_WRAP));
            } else {
                s = "";
            }
            mSelectionHelper.processExternalAction(action, s);
        }
    }


    @SuppressWarnings({"deprecation", "FieldCanBeLocal"})
    private final WebChromeClient mWebChromeClient = new WebChromeClient() {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            mWrappedWebChromeClient.onProgressChanged(view, newProgress);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            mWrappedWebChromeClient.onReceivedTitle(view, title);
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            mWrappedWebChromeClient.onReceivedIcon(view, icon);
        }

        @Override
        public void onReceivedTouchIconUrl(WebView view, String url, boolean precomposed) {
            mWrappedWebChromeClient.onReceivedTouchIconUrl(view, url, precomposed);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            mWrappedWebChromeClient.onShowCustomView(view, callback);
        }

        @Override
        public void onShowCustomView(View view, int requestedOrientation,
                CustomViewCallback callback) {
            mWrappedWebChromeClient.onShowCustomView(view, requestedOrientation, callback);
        }

        @Override
        public void onHideCustomView() {
            mWrappedWebChromeClient.onHideCustomView();
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                boolean isUserGesture, Message resultMsg) {
            return mWrappedWebChromeClient.onCreateWindow(view, isDialog, isUserGesture, resultMsg);
        }

        @Override
        public void onRequestFocus(WebView view) {
            mWrappedWebChromeClient.onRequestFocus(view);
        }

        @Override
        public void onCloseWindow(WebView window) {
            mWrappedWebChromeClient.onCloseWindow(window);
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            return mWrappedWebChromeClient.onJsAlert(view, url, message, result);
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            return mWrappedWebChromeClient.onJsConfirm(view, url, message, result);
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                JsPromptResult result) {
            return mWrappedWebChromeClient.onJsPrompt(view, url, message, defaultValue, result);
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            return mWrappedWebChromeClient.onJsBeforeUnload(view, url, message, result);
        }

        @Override
        public void onExceededDatabaseQuota(String url, String databaseIdentifier, long quota,
                long estimatedDatabaseSize, long totalQuota, WebStorage.QuotaUpdater quotaUpdater) {
            mWrappedWebChromeClient.onExceededDatabaseQuota(url, databaseIdentifier, quota,
                    estimatedDatabaseSize, totalQuota, quotaUpdater);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                GeolocationPermissions.Callback callback) {
            mWrappedWebChromeClient.onGeolocationPermissionsShowPrompt(origin, callback);
        }

        @Override
        public void onGeolocationPermissionsHidePrompt() {
            mWrappedWebChromeClient.onGeolocationPermissionsHidePrompt();
        }

        @Override
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public void onPermissionRequest(PermissionRequest request) {
            mWrappedWebChromeClient.onPermissionRequest(request);
        }

        @Override
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public void onPermissionRequestCanceled(PermissionRequest request) {
            mWrappedWebChromeClient.onPermissionRequestCanceled(request);
        }

        @Override
        public boolean onJsTimeout() {
            return mWrappedWebChromeClient.onJsTimeout();
        }

        @Override
        public void onConsoleMessage(String message, int lineNumber, String sourceID) {
            filterAceMessage(message);
            mWrappedWebChromeClient.onConsoleMessage(message, lineNumber, sourceID);
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            filterAceMessage(consoleMessage.message());
            return mWrappedWebChromeClient.onConsoleMessage(consoleMessage);
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return mWrappedWebChromeClient.getDefaultVideoPoster();
        }

        @Override
        public View getVideoLoadingProgressView() {
            return mWrappedWebChromeClient.getVideoLoadingProgressView();
        }

        @Override
        public void getVisitedHistory(ValueCallback<String[]> callback) {
            mWrappedWebChromeClient.getVisitedHistory(callback);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
            return mWrappedWebChromeClient.onShowFileChooser(
                    webView, filePathCallback, fileChooserParams);
        }
    };

    @SuppressWarnings({"deprecation", "FieldCanBeLocal"})
    private final WebViewClient mWebViewClient = new WebViewClient() {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return mWrappedWebViewClient.shouldOverrideUrlLoading(view, url);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.N)
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return mWrappedWebViewClient.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            // Need to proper load the ace editor in version lower than KitKat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                try {
                    Thread.sleep(350L);
                } catch (InterruptedException e) {
                    // ignore
                }
            }
            mWrappedWebViewClient.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mWrappedWebViewClient.onPageFinished(view, url);
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            mWrappedWebViewClient.onLoadResource(view, url);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.M)
        public void onPageCommitVisible(WebView view, String url) {
            mWrappedWebViewClient.onPageCommitVisible(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return mWrappedWebViewClient.shouldInterceptRequest(view, url);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public WebResourceResponse shouldInterceptRequest(WebView view,
                WebResourceRequest request) {
            return mWrappedWebViewClient.shouldInterceptRequest(view, request);
        }

        @Override
        public void onTooManyRedirects(WebView view, Message cancelMsg, Message continueMsg) {
            mWrappedWebViewClient.onTooManyRedirects(view, cancelMsg, continueMsg);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                String failingUrl) {
            mWrappedWebViewClient.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.M)
        public void onReceivedError(WebView view, WebResourceRequest request,
                WebResourceError error) {
            mWrappedWebViewClient.onReceivedError(view, request, error);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.M)
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                WebResourceResponse errorResponse) {
            mWrappedWebViewClient.onReceivedHttpError(view, request, errorResponse);
        }

        @Override
        public void onFormResubmission(WebView view, Message dontResend, Message resend) {
            mWrappedWebViewClient.onFormResubmission(view, dontResend, resend);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            mWrappedWebViewClient.doUpdateVisitedHistory(view, url, isReload);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            mWrappedWebViewClient.onReceivedSslError(view, handler, error);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
            mWrappedWebViewClient.onReceivedClientCertRequest(view, request);
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                String host, String realm) {
            mWrappedWebViewClient.onReceivedHttpAuthRequest(view, handler, host, realm);
        }

        @Override
        public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
            return mWrappedWebViewClient.shouldOverrideKeyEvent(view, event);
        }

        @Override
        public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
            mWrappedWebViewClient.onUnhandledKeyEvent(view, event);
        }

        @Override
        public void onScaleChanged(WebView view, float oldScale, float newScale) {
            mWrappedWebViewClient.onScaleChanged(view, oldScale, newScale);
        }

        @Override
        public void onReceivedLoginRequest(WebView view, String realm, String account,
                String args) {
            mWrappedWebViewClient.onReceivedLoginRequest(view, realm, account, args);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.O)
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            return mWrappedWebViewClient.onRenderProcessGone(view, detail);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.O_MR1)
        public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType,
                SafeBrowsingResponse callback) {
            mWrappedWebViewClient.onSafeBrowsingHit(view, request, threatType, callback);
        }
    };
}
