package com.ruesga.rview.misc;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.ColorRes;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.ruesga.rview.R;

public class ThemedSwipeRefreshLayout extends SwipeRefreshLayout {

    public ThemedSwipeRefreshLayout(Context context) {
        this(context, null);
    }

    public ThemedSwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        final int[] styledAttrs = {android.R.attr.colorPrimary};

        TypedArray a = context.obtainStyledAttributes(styledAttrs);
        @ColorRes int colorId = a.getResourceId(0, -1);
        if (colorId == -1) {
            colorId = R.color.primary;
        }
        a.recycle();
        setColorSchemeResources(colorId);
        setProgressBackgroundColorSchemeColor(getBackground(getContext()));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return false;
    }

    public static int getBackground(Context context) {
        TypedArray array = context.obtainStyledAttributes(new int[]{android.R.attr.colorBackgroundFloating});
        int color = array.getColor(0, 0);
        array.recycle();
        return color;
    }
}