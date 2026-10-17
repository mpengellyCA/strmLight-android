package com.limelight.strmqt;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;

/**
 * StrmQt's look for StrmLight's own screens ("Projection Booth", StrmQt's
 * docs/BRANDING.md): its three typefaces, bundled under assets/fonts with
 * their licences, and its wordmark, "Strm" in cream and "Light" in amber as
 * StrmQt sets "Strm" and "Qt".
 *
 * The colours, shapes and focus rings are resources (strmqt_colors.xml,
 * strmqt_styles.xml, drawable/strmqt_*); a view's android:tag names its
 * typeface, read here.
 */
public final class Brand {
    public static final String TAG_DISPLAY = "display";
    public static final String TAG_MONO = "mono";
    public static final String TAG_MONO_BOLD = "mono-bold";
    public static final String TAG_BODY_MEDIUM = "body-medium";

    private static Typeface display;
    private static Typeface body;
    private static Typeface bodyMedium;
    private static Typeface mono;
    private static Typeface monoBold;
    private static boolean loaded;

    private Brand() {
    }

    private static synchronized void load(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        AssetManager assets = context.getApplicationContext().getAssets();
        // Archivo DemiBold and Public Sans Regular / Medium, as StrmQt sets them
        // (Theme.fontDisplay at weight 600, Theme.fontBody).
        display = variable(assets, "fonts/Archivo.ttf", "'wght' 600, 'wdth' 100", Typeface.BOLD);
        body = variable(assets, "fonts/PublicSans.ttf", "'wght' 400", Typeface.NORMAL);
        bodyMedium = variable(assets, "fonts/PublicSans.ttf", "'wght' 500", Typeface.NORMAL);
        mono = file(assets, "fonts/IBMPlexMono-Regular.ttf", Typeface.MONOSPACE);
        monoBold = file(assets, "fonts/IBMPlexMono-SemiBold.ttf", Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    /** One instance of a variable font; its default instance, styled, before Android 8. */
    private static Typeface variable(AssetManager assets, String path, String axes, int fallbackStyle) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Typeface face = new Typeface.Builder(assets, path).setFontVariationSettings(axes).build();
                if (face != null) {
                    return face;
                }
            }
            return Typeface.create(Typeface.createFromAsset(assets, path), fallbackStyle);
        } catch (RuntimeException e) {
            LimeLog.warning("StrmQt: cannot load " + path + ": " + e);
            return Typeface.create(Typeface.SANS_SERIF, fallbackStyle);
        }
    }

    private static Typeface file(AssetManager assets, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(assets, path);
        } catch (RuntimeException e) {
            LimeLog.warning("StrmQt: cannot load " + path + ": " + e);
            return fallback;
        }
    }

    public static Typeface typeface(Context context, String role) {
        load(context);
        if (TAG_DISPLAY.equals(role)) {
            return display;
        } else if (TAG_MONO.equals(role)) {
            return mono;
        } else if (TAG_MONO_BOLD.equals(role)) {
            return monoBold;
        } else if (TAG_BODY_MEDIUM.equals(role)) {
            return bodyMedium;
        }
        return body;
    }

    /** Sets each text view under `root` in the typeface its tag names; Public Sans if none. */
    public static void applyFonts(View root) {
        if (root instanceof TextView) {
            TextView text = (TextView) root;
            Object tag = text.getTag();
            text.setTypeface(typeface(text.getContext(), tag instanceof String ? (String) tag : null));
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyFonts(group.getChildAt(i));
            }
        }
    }

    /**
     * Lets a focused button's ring and lift (StrmQt's focus scale) show past
     * its parents' edges. A scrolling view still clips what it scrolls, but
     * not inside its padding.
     */
    public static void unclip(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) root;
        if (group instanceof android.widget.ScrollView) {
            // Its parent no longer clips it, so it clips itself, to its own edges.
            group.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
            group.setClipToOutline(true);
        }
        group.setClipChildren(false);
        group.setClipToPadding(false);
        for (int i = 0; i < group.getChildCount(); i++) {
            unclip(group.getChildAt(i));
        }
    }

    /** "StrmLight": "Strm" in cream, "Light" in amber, set in Archivo by the view it goes in. */
    public static CharSequence wordmark(Context context) {
        SpannableStringBuilder text = new SpannableStringBuilder("Strm");
        text.setSpan(new ForegroundColorSpan(context.getResources().getColor(R.color.strmqt_text)),
                0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = text.length();
        text.append("Light");
        text.setSpan(new ForegroundColorSpan(context.getResources().getColor(R.color.strmqt_accent)),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /**
     * A short message in StrmQt's toast look (a dark panel with a hairline).
     * Android 11 and later no longer show a toast's own view, so there it is
     * the system's plain toast.
     */
    @SuppressWarnings("deprecation")
    public static void toast(Context context, CharSequence message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            return;
        }
        TextView view = new TextView(context);
        view.setText(message);
        view.setTextColor(context.getResources().getColor(R.color.strmqt_text));
        view.setTextSize(18);
        view.setBackgroundResource(R.drawable.strmqt_chip);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        view.setPadding(pad * 3 / 2, pad, pad * 3 / 2, pad);
        view.setTypeface(typeface(context, null));
        Toast toast = new Toast(context);
        toast.setView(view);
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, pad * 3);
        toast.show();
    }
}
