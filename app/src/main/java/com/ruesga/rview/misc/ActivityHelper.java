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
package com.ruesga.rview.misc;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.widget.Toast;

import com.ruesga.rview.ChangeDetailsActivity;
import com.ruesga.rview.ChangeListByFilterActivity;
import com.ruesga.rview.DiffViewerActivity;
import com.ruesga.rview.EditorActivity;
import com.ruesga.rview.R;
import com.ruesga.rview.SearchActivity;
import com.ruesga.rview.TabFragmentActivity;
import com.ruesga.rview.fragments.DashboardFragment;
import com.ruesga.rview.fragments.RelatedChangesFragment;
import com.ruesga.rview.fragments.StatsFragment;
import com.ruesga.rview.gerrit.filter.ChangeQuery;
import com.ruesga.rview.gerrit.model.ChangeInfo;
import com.ruesga.rview.gerrit.model.DashboardInfo;
import com.ruesga.rview.gerrit.model.FileInfo;
import com.ruesga.rview.model.Account;
import com.ruesga.rview.preferences.Constants;
import com.ruesga.rview.preferences.Preferences;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.app.NavUtils;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class ActivityHelper {

    public static final int LIST_RESULT_CODE = 100;
    private static final String ACTION_CUSTOM_TABS_CONNECTION = "android.support.customtabs.action.CustomTabsService";

    public static void openUriInCustomTabs(Activity activity, String uri) {
        openUriInCustomTabs(activity, Uri.parse(uri), false);
    }

    public static void openUriInCustomTabs(Activity activity, String uri, boolean excludeRview) {
        openUriInCustomTabs(activity, Uri.parse(uri), excludeRview);
    }

    public static void openUriInCustomTabs(Activity activity, Uri uri) {
        openUriInCustomTabs(activity, uri, false);
    }

    public static void openUriInCustomTabs(Activity activity, Uri uri, boolean excludeRview) {
        // Check user preferences
        if (!Preferences.isAccountUseCustomTabs(activity, Preferences.getAccount(activity))) {
            openUri(activity, uri, excludeRview);
            return;
        }

        try {
            CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
            builder.setShowTitle(true);
            builder.setToolbarColor(ContextCompat.getColor(activity, R.color.primaryDark));
            CustomTabsIntent intent = builder.build();

            String packageName = CustomTabsHelper.getPackageNameToUse(activity);
            if (packageName == null) {
                openUri(activity, uri, excludeRview);
                return;
            }

            intent.intent.setPackage(packageName);
            if (excludeRview) {
                intent.intent.putExtra(Constants.EXTRA_SOURCE, activity.getPackageName());
            }
            intent.intent.putExtra(Constants.EXTRA_FORCE_SINGLE_PANEL, true);
            intent.launchUrl(activity, uri);

        } catch (ActivityNotFoundException ex) {
            // Fallback to default browser
            openUri(activity, uri, excludeRview);
        }
    }
    public static ArrayList<ResolveInfo> getCustomTabsPackages(Context ctx, ArrayList<ResolveInfo> packagesSupportingCustomTabs) {
        PackageManager pm = ctx.getPackageManager();
        // Get default VIEW intent handler.
        Intent activityIntent = new Intent()
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("http", "", null));

        // Get all apps that can handle VIEW intents.
        List<ResolveInfo> resolvedActivityList = pm.queryIntentActivities(activityIntent, 0);
        for (ResolveInfo info : resolvedActivityList) {
            Intent serviceIntent = new Intent();
            serviceIntent.setAction(ACTION_CUSTOM_TABS_CONNECTION);
            serviceIntent.setPackage(info.activityInfo.packageName);
            // Check if this package also resolves the Custom Tabs service.
            if (pm.resolveService(serviceIntent, 0) != null) {
                packagesSupportingCustomTabs.add(info);
            }
        }
        return packagesSupportingCustomTabs;
    }

    @SuppressWarnings("Convert2streamapi")
    public static void openUri(Context ctx, Uri uri, boolean excludeRview) {
        try {
            Intent intent = new Intent()
                    .setAction(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.fromParts("http", "", null));

            if (excludeRview) {
                getCustomTabsPackages(ctx);
                ctx.startActivity(getCustomTabsPackages(ctx,));
            } else {
                ctx.startActivity(intent);
            }

        } catch (ActivityNotFoundException ex) {
            // Fallback to default browser
            String msg = ctx.getString(R.string.exception_browser_not_found, uri.toString());
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        }
    }

    public static void handleUri(Context ctx, Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.putExtra(Constants.EXTRA_FORCE_SINGLE_PANEL, true);
            intent.putExtra(Constants.EXTRA_HAS_PARENT, true);
            intent.putExtra(Constants.EXTRA_SOURCE, ctx.getPackageName());
            intent.setPackage(ctx.getPackageName());
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            // Fallback to default browser
            String msg = ctx.getString(R.string.exception_browser_not_found, uri.toString());
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        }
    }
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void open(Context ctx, String action, Uri uri, String mimeType) {
                try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        if (mimeType == null) {
                                intent.setData(uri);
                            } else {
                                intent.setDataAndType(uri, mimeType);
                            }
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                            } else {
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                            }
                        ctx.startActivity(Intent.createChooser(intent, action));

                            } catch (ActivityNotFoundException ex) {
                        String msg = ctx.getString(R.string.exception_cannot_handle_link, uri.toString());
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                   }
            }

                @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void share(Context ctx, String action, String title, String text) {
                try {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("text/plain");
                        intent.putExtra(Intent.EXTRA_SUBJECT, title);
                        intent.putExtra(Intent.EXTRA_TEXT, text);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                            } else {
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                            }
                        ctx.startActivity(Intent.createChooser(intent, action));

                            } catch (ActivityNotFoundException ex) {
                        String msg = ctx.getString(R.string.exception_cannot_share_link);
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                    }
            }
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void downloadUri(Context context, Uri uri, String fileName,
            @Nullable String mimeType) throws IOException {
        // Create the destination location
        File destination = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (destination == null) {
            throw new IOException("Extaernal directory not available");
        }
        destination.mkdirs();
        Uri destinationUri = Uri.fromFile(new File(destination, fileName));

        // Use the download manager to perform the download
        DownloadManager downloadManager =
                (DownloadManager) context.getSystemService(Activity.DOWNLOAD_SERVICE);
        if (downloadManager != null) {
            Request request = new Request(uri)
                    .setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationUri(destinationUri);
            if (mimeType != null) {
                request.setMimeType(mimeType);
            }
            //noinspection deprecation
            request.allowScanningByMediaScanner();
            downloadManager.enqueue(request);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void downloadLocalFile(Context context, File src, String name) throws IOException {
        File downloadFolder = context.getExternalFilesDir(
                Environment.DIRECTORY_DOWNLOADS);
        if (downloadFolder == null) {
            throw new IOException("Extaernal directory not available");
        }
        downloadFolder.mkdirs();
        File dst = new File(downloadFolder, name);
        FileUtils.copyFile(src, dst);
        String mimeType = StringHelper.getMimeType(dst);

        DownloadManager downloadManager =
                (DownloadManager) context.getSystemService(Activity.DOWNLOAD_SERVICE);
        downloadManager.addCompletedDownload(
                dst.getName(), dst.getName(), true, mimeType, dst.getPath(), dst.length(), true);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void openChangeDetailsByUri(
            Context context, Uri uri, boolean hasParent, boolean hasForceUp) {
                Intent intent = new Intent(context, ChangeDetailsActivity.class);

                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                    } else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                    }
                intent.putExtra(Constants.EXTRA_FORCE_SINGLE_PANEL, true);
                intent.putExtra(Constants.EXTRA_HAS_PARENT, hasParent);
                intent.putExtra(Constants.EXTRA_HAS_FORCE_UP, hasForceUp);
                intent.setData(uri);
                context.startActivity(intent);
            }
    public static boolean performFinishActivity(Activity activity, boolean forceNavigateUp) {
        if (activity == null) {
            return false;
        }
        boolean hasParent = activity.getIntent().getBooleanExtra(Constants.EXTRA_HAS_PARENT, false);
        if (forceNavigateUp || !hasParent) {
            Intent upIntent = NavUtils.getParentActivityIntent(activity);
            if (upIntent != null) {
                if (!hasParent) {
                    upIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TASK);
                }

                if (NavUtils.shouldUpRecreateTask(activity, upIntent)) {
                    TaskStackBuilder.create(activity)
                            .addNextIntentWithParentStack(upIntent)
                            .startActivities();
                } else {
                    activity.startActivity(upIntent);
                }
            }
        }
        activity.finish();
        return true;
    }

    public static void openChangeDetails(Context context, ChangeInfo change,
            boolean withParent, boolean forceSinglePanel) {
        openChangeDetails(context, change.changeId, change.legacyChangeId,
                withParent, forceSinglePanel);
    }

    public static void openChangeDetails(Context context, String changeId, int legacyChangeId,
            boolean withParent, boolean forceSinglePanel) {
        Intent intent = new Intent(context, ChangeDetailsActivity.class);
        intent.putExtra(Constants.EXTRA_CHANGE_ID, changeId);
        intent.putExtra(Constants.EXTRA_LEGACY_CHANGE_ID, legacyChangeId);
        intent.putExtra(Constants.EXTRA_HAS_PARENT, withParent);
        intent.putExtra(Constants.EXTRA_FORCE_SINGLE_PANEL, forceSinglePanel);
        context.startActivity(intent);
    }

    public static void editChange( Fragment fragment, int legacyChangeId,
            String changeId, String revisionId, int requestCode) {
        Intent intent = new Intent(fragment.getContext(), EditorActivity.class);
        intent.putExtra(Constants.EXTRA_CHANGE_ID, changeId);
        intent.putExtra(Constants.EXTRA_LEGACY_CHANGE_ID, legacyChangeId);
        intent.putExtra(Constants.EXTRA_REVISION_ID, revisionId);
        intent.putExtra(Constants.EXTRA_HAS_PARENT, true);
        fragment.startActivityForResult(intent, requestCode);
    }

    public static void viewChangeFile(Fragment fragment, int legacyChangeId,
            String changeId, String revisionId, String fileName, File content) {
        Intent intent = new Intent(fragment.getContext(), EditorActivity.class);
        intent.putExtra(Constants.EXTRA_CHANGE_ID, changeId);
        intent.putExtra(Constants.EXTRA_LEGACY_CHANGE_ID, legacyChangeId);
        intent.putExtra(Constants.EXTRA_REVISION_ID, revisionId);
        intent.putExtra(Constants.EXTRA_FILE, fileName);
        intent.putExtra(Constants.EXTRA_CONTENT_FILE, content.getAbsolutePath());
        intent.putExtra(Constants.EXTRA_READ_ONLY, true);
        intent.putExtra(Constants.EXTRA_HAS_PARENT, true);
        fragment.startActivity(intent);
    }

    public static void openChangeListByFilterActivity(Activity activity, String title,
            ChangeQuery filter, boolean dirty, boolean clearStack) {
        Intent intent = new Intent(activity, ChangeListByFilterActivity.class);
        if (clearStack) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else {
            intent.putExtra(Constants.EXTRA_HAS_PARENT, true);
        }
        intent.putExtra(Constants.EXTRA_TITLE, title);
        intent.putExtra(Constants.EXTRA_FILTER, filter.toString());
        intent.putExtra(Constants.EXTRA_DIRTY, dirty);
        activity.startActivityForResult(intent, LIST_RESULT_CODE);
    }

    public static void openRelatedChangesActivity(
            Context ctx, ChangeInfo change, String revisionId) {
        Intent intent = new Intent(ctx, TabFragmentActivity.class);

        final String title = ctx.getString(R.string.change_details_title, change.legacyChangeId);
        ArrayList<String> args = new ArrayList<>(
                Arrays.asList(
                        String.valueOf(change.legacyChangeId),
                        change.changeId,
                        change.project,
                        revisionId,
                        change.topic));
        intent.putExtra(Constants.EXTRA_TITLE, title);
        intent.putExtra(Constants.EXTRA_SUBTITLE, change.changeId);
        intent.putExtra(Constants.EXTRA_FRAGMENT, RelatedChangesFragment.class.getName());
        intent.putStringArrayListExtra(Constants.EXTRA_FRAGMENT_ARGS, args);
        intent.putExtra(Constants.EXTRA_HAS_PARENT, true);
        ctx.startActivity(intent);
    }

    public static void openStatsActivity(Context ctx, String title, String subtitle,
            int type, String id, ChangeQuery filter, String extra) {
        Intent intent = new Intent(ctx, TabFragmentActivity.class);

        ArrayList<String> args = new ArrayList<>(
                Arrays.asList(
                        String.valueOf(type),
                        id,
                        filter.toString(),
                        extra));
        intent.putExtra(Constants.EXTRA_TITLE, title);
        intent.putExtra(Constants.EXTRA_SUBTITLE, subtitle);
        intent.putExtra(Constants.EXTRA_FRAGMENT, StatsFragment.class.getName());
        intent.putStringArrayListExtra(Constants.EXTRA_FRAGMENT_ARGS, args);
        intent.putExtra(Constants.EXTRA_HAS_PARENT, true);
        ctx.startActivity(intent);
    }

    public static void openDiffViewerActivity(Fragment fragment, ChangeInfo change,
            ArrayList<String> files, Map<String, FileInfo> info, String revisionId, String base, String current,
            String file, String comment, int requestCode) {
        Intent intent = getOpenDiffViewerActivityIntent(fragment.getContext(), change, files, info,
                revisionId, base, current, file, comment, requestCode);
        fragment.startActivityForResult(intent, requestCode);
    }

    public static void openDiffViewerActivity(Activity activity, ChangeInfo change,
            ArrayList<String> files, Map<String, FileInfo> info, String revisionId, String base, String current,
            String file, String comment, int requestCode) {
        Intent intent = getOpenDiffViewerActivityIntent(activity, change, files, info,
                revisionId, base, current, file, comment, requestCode);
        activity.startActivityForResult(intent, requestCode);
    }

    private static Intent getOpenDiffViewerActivityIntent(Context context, ChangeInfo change,
            ArrayList<String> files, Map<String, FileInfo> info, String revisionId, String base, String current,
            String file, String comment, int requestCode) {
        Intent intent = new Intent(context, DiffViewerActivity.class);
        intent.putExtra(Constants.EXTRA_REVISION_ID, revisionId);
        intent.putExtra(Constants.EXTRA_BASE, base);
        intent.putExtra(Constants.EXTRA_FILE, file);
        if (comment != null) {
            intent.putExtra(Constants.EXTRA_COMMENT, comment);
        }
        intent.putExtra(Constants.EXTRA_HAS_PARENT, requestCode != 0);

        try {
            CacheHelper.writeAccountDiffCacheFile(context, CacheHelper.CACHE_CHANGE_JSON,
                    SerializationManager.getInstance().toJson(change).getBytes());

            if (files != null) {
                String prefix = (base == null ? "0" : base) + "_" + current + "_";
                CacheHelper.writeAccountDiffCacheFile(context, prefix + CacheHelper.CACHE_FILES_JSON,
                        SerializationManager.getInstance().toJson(files).getBytes());
            }
            if (info != null) {
                String prefix = (base == null ? "0" : base) + "_" + current + "_";
                CacheHelper.writeAccountDiffCacheFile(context, prefix + CacheHelper.CACHE_FILES_INFO_JSON,
                        SerializationManager.getInstance().toJson(info).getBytes());
            }
        } catch (IOException ex) {
            // Ignore
        }
        return intent;
    }

    public static void openSearchActivity(Activity activity) {
        Intent intent = new Intent(activity, SearchActivity.class);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }

    public static void openDashboardActivity(
            Context ctx, DashboardInfo dashboard, boolean clearStack) {
        Intent intent = new Intent(ctx, TabFragmentActivity.class);
        if (clearStack) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else {
            intent.putExtra(Constants.EXTRA_HAS_PARENT, true);
        }

        ArrayList<String> args = new ArrayList<>(
                Collections.singletonList(SerializationManager.getInstance().toJson(dashboard)));
        intent.putExtra(Constants.EXTRA_TITLE, ctx.getString(R.string.menu_dashboard));
        intent.putExtra(Constants.EXTRA_SUBTITLE, dashboard.title);
        intent.putExtra(Constants.EXTRA_FRAGMENT, DashboardFragment.class.getName());
        intent.putStringArrayListExtra(Constants.EXTRA_FRAGMENT_ARGS, args);
        ctx.startActivity(intent);
    }

    public static Uri resolveRepositoryUri(Context context, String uri) {
        Uri u = Uri.parse(uri);
        if (u.getAuthority() == null) {
            Account account = Preferences.getAccount(context);
            if (account != null) {
                String authority = account.mRepository.mUrl;
                if (!authority.endsWith("/")) {
                    authority += "/";
                }

                String path = uri;
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }

                u = Uri.parse(authority + path);
            }
        }
        return u;
    }
}
