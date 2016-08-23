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
package com.ruesga.rview.wizard.validators;

import android.content.Context;
import android.widget.EditText;

import com.ruesga.rview.wizard.R;

public class WebUrlValidator implements Validator<EditText> {

    private final Context mContext;

    public WebUrlValidator(Context context) {
        mContext = context;
    }

    @Override
    public boolean validate(EditText v) {
        final CharSequence s = v.getText();
        return android.util.Patterns.WEB_URL.matcher(s).matches();
    }

    @Override
    public String getMessage() {
        return mContext.getString(R.string.validator_invalid_web_url);
    }
}
