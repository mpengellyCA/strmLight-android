package com.limelight.strmqt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.util.AttributeSet;
import android.widget.Toast;

import com.limelight.R;

/** A settings row that does something when chosen: `action` is "setup", "pairagain" or "preset". */
public class ActionPreference extends Preference {
    private final String action;

    public ActionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        action = attrs.getAttributeValue(null, "action");
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        if ("setup".equals(action)) {
            context.startActivity(new Intent(context, SetupActivity.class));
        } else if ("pairagain".equals(action)) {
            new StrmQtPrefs(context).forgetToken();
            context.startActivity(new Intent(context, SetupActivity.class)
                    .putExtra(SetupActivity.EXTRA_PAIR_NOW, true));
        } else if ("preset".equals(action)) {
            boolean uhd = StreamPreset.apply(context);
            Toast.makeText(context, uhd ? R.string.strmqt_preset_applied_4k
                    : R.string.strmqt_preset_applied_1080p, Toast.LENGTH_LONG).show();
            if (context instanceof Activity) {
                // The rows above still show the old values.
                ((Activity) context).recreate();
            }
        }
    }
}
