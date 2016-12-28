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
package com.ruesga.rview;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ArrayAdapter;

import com.ruesga.rview.gerrit.filter.ChangeQuery;
import com.ruesga.rview.gerrit.filter.antlr.QueryParseException;
import com.ruesga.rview.misc.ActivityHelper;
import com.ruesga.rview.misc.StringHelper;
import com.ruesga.rview.misc.UriHelper;
import com.ruesga.rview.model.Account;
import com.ruesga.rview.preferences.Constants;
import com.ruesga.rview.preferences.Preferences;
import com.ruesga.rview.widget.RegExLinkifyTextView;

import java.util.ArrayList;
import java.util.List;

public class UrlHandlerProxyActivity extends AppCompatDelegateActivity {

    private static final String TAG = "UrlHandlerProxyActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check we have valid arguments
        if (getIntent() == null || getIntent().getData() == null) {
            finish();
            return;
        }

        // Check we have something we allow to handle
        final Uri uri = getIntent().getData();
        String scheme = uri.getScheme();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            finish();
            return;
        }

        // If we don't have an account, then we can handle the link for sure
        Account account = Preferences.getAccount(this);
        if (account == null) {
            openExternalHttpLinkAndFinish(uri);
            return;
        }

        // Check that we have an activity account which can handle the request
        List<Account> accounts = Preferences.getAccounts(this);
        List<Account> targetAccounts = new ArrayList<>();
        String type = "";
        for (Account acct : accounts) {
            List<RegExLinkifyTextView.RegExLink> links =
                    RegExLinkifyTextView.createRepositoryRegExpLinks(acct.mRepository);
            for (RegExLinkifyTextView.RegExLink link : links) {
                if (link.mPattern.matcher(uri.toString()).find()) {
                    targetAccounts.add(acct);

                    // We can assume safely that all matches are of the same type
                    type = link.mType;
                }
            }
        }

        // No accounts are able to handle the link
        if (targetAccounts.isEmpty()) {
            openExternalHttpLinkAndFinish(uri);
            return;
        }

        // Should we change account
        boolean isSameAccount = false;
        for (Account acct : targetAccounts) {
            if (account.getAccountHash().equals(acct.getAccountHash())) {
                isSameAccount = true;
                break;
            }
        }

        if (!isSameAccount) {
            // Open a dialog to ask the user which of the configure accounts wants
            // to use to open the uri
            if (targetAccounts.size() > 1) {
                final String t = type;
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        this, R.layout.account_chooser_item_layout);
                for (Account acct : targetAccounts) {
                    final String name = getString(R.string.account_settings_subtitle,
                            acct.getRepositoryDisplayName(), acct.getAccountDisplayName());
                    adapter.add(name);
                }

                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.account_choose_title)
                        .setSingleChoiceItems(adapter, -1, (d, which) -> {
                            d.dismiss();

                            // Change to the selected account.
                            final Context ctx = UrlHandlerProxyActivity.this;
                            Preferences.setAccount(ctx, targetAccounts.get(which));

                            // An now handle the dialog
                            handleUri(t, uri);
                            finish();
                        })
                        .setPositiveButton(R.string.action_cancel, (d, which) -> {
                            d.dismiss();
                            finish();
                        })
                        .setOnCancelListener(d -> finish())
                        .setOnDismissListener(d -> finish())
                        .create();
                dialog.show();
                return;

            } else {
                // Use the unique account found
                Preferences.setAccount(this, targetAccounts.get(0));
            }
        }

        // Open the change details
        handleUri(type, uri);
        finish();
    }

    private void handleUri(String type, Uri uri) {
        // Open the change details
        switch (type) {
            case Constants.CUSTOM_URI_CHANGE_ID:
                ActivityHelper.openChangeDetailsByUri(
                        this, UriHelper.createCustomUri(this, Constants.CUSTOM_URI_CHANGE_ID,
                                UriHelper.extractChangeId(uri)));
                break;

            case Constants.CUSTOM_URI_QUERY:
                String query = UriHelper.extractQuery(uri);
                if (!TextUtils.isEmpty(query)) {
                    final ChangeQuery filter;
                    if (isCommit(query)) {
                        filter = new ChangeQuery().commit(query);
                    } else if (isChange(query) || isChangeId(query)) {
                        filter = new ChangeQuery().change(query);
                    } else {
                        // Try to parse the query
                        try {
                            filter = ChangeQuery.parse(query);
                        } catch (QueryParseException ex) {
                            // Ignore. Try to open the url.
                            Log.w(TAG, "Can parse query: " + query);
                            openExternalHttpLinkAndFinish(uri);
                            return;
                        }
                    }

                    ActivityHelper.openChangeListByFilterActivity(this, null, filter, true);
                    break;
                }
                // fallback to default

            default:
                // We cannot handle this
                openExternalHttpLinkAndFinish(uri);
                break;
        }
    }

    private void openExternalHttpLinkAndFinish(Uri link) {
        String source = getIntent().getStringExtra(Constants.EXTRA_SOURCE);
        if (source != null && source.equals(getPackageName())) {
            ActivityHelper.openUriInCustomTabs(this, link, true);
        } else {
            ActivityHelper.openUri(this, link, true);
        }
        finish();
    }

    private static boolean isChange(String query) {
        return StringHelper.GERRIT_CHANGE.matcher(query).matches();
    }

    private static boolean isCommit(String query) {
        return StringHelper.GERRIT_COMMIT.matcher(query).matches();
    }

    private static boolean isChangeId(String query) {
        return StringHelper.GERRIT_CHANGE_ID.matcher(query).matches();
    }

}
